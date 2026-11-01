using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pointr;

/// <summary>
/// Network side of Pointr.
///   UDP 47800  discovery: "PNTR_DISCOVER" → {"t":"pointr",id,name,port}
///   TCP 47801  JSON lines: handshake, pairing, commands, state pushes
///   UDP 47802  signed binary packets for pointer motion (lowest latency)
/// </summary>
internal sealed class Server(Config config, InputService input, AudioService audio, WindowService windows,
    BlurOverlay blur, LaserOverlay laser, Control ui)
{
    public const int DiscoveryPort = 47800, TcpPort = 47801, UdpPort = 47802;
    private const int MaxLine = 64 * 1024;

    private readonly ConcurrentDictionary<uint, Session> _sessions = new();
    private CancellationTokenSource _screenGate = new(); // cancelled when screen sharing is switched off

    public ScreenStreamer Screen { get; private set; } = null!;
    private (float, bool)? _lastVolume;

    public event Action? SessionsChanged;
    public IEnumerable<string> ConnectedNames => _sessions.Values.Select(s => s.DeviceName).Distinct();

    public void Start()
    {
        new Thread(DiscoveryLoop) { IsBackground = true, Name = "discovery" }.Start();
        new Thread(UdpLoop) { IsBackground = true, Name = "udp", Priority = ThreadPriority.AboveNormal }.Start();
        _ = AcceptLoop();
        Screen = new ScreenStreamer(AuthorizeScreen);
        Screen.Start();
        blur.Changed += on => Broadcast(new JsonObject { ["t"] = "blur", ["on"] = on });
        _volumeTimer = new System.Threading.Timer(_ => PushVolumeIfChanged(), null, 1000, 700);
    }

    private System.Threading.Timer? _volumeTimer; // polled: catches changes made on the PC itself

    public bool AllowScreen
    {
        get { lock (config) return config.AllowScreen; }
        set
        {
            lock (config) { config.AllowScreen = value; config.Save(); }
            if (value) return;
            var old = _screenGate;
            _screenGate = new CancellationTokenSource();
            old.Cancel(); // ends every running stream
        }
    }

    private CancellationToken? AuthorizeScreen(uint sid, byte[] proof)
    {
        if (!AllowScreen || !_sessions.TryGetValue(sid, out var s)) return null;
        if (!CryptographicOperations.FixedTimeEquals(ScreenStreamer.Proof(s.UdpKey, sid), proof)) return null;
        return CancellationTokenSource.CreateLinkedTokenSource(s.Gone.Token, _screenGate.Token).Token;
    }

    public void Forget(string deviceId)
    {
        lock (config)
        {
            config.Devices.RemoveAll(d => d.Id == deviceId);
            config.Save();
        }
        foreach (var s in _sessions.Values.Where(s => s.DeviceId == deviceId)) s.Close();
    }

    // ───────────────────────── discovery ─────────────────────────

    private void DiscoveryLoop()
    {
        using var sock = new UdpClient(AddressFamily.InterNetwork);
        sock.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        sock.Client.Bind(new IPEndPoint(IPAddress.Any, DiscoveryPort));
        var reply = Encoding.UTF8.GetBytes(new JsonObject
        {
            ["t"] = "pointr", ["id"] = config.ServerId, ["name"] = Environment.MachineName, ["port"] = TcpPort,
        }.ToJsonString());
        while (true)
        {
            try
            {
                var from = new IPEndPoint(IPAddress.Any, 0);
                var data = sock.Receive(ref from);
                if (Encoding.ASCII.GetString(data).StartsWith("PNTR_DISCOVER")) sock.Send(reply, reply.Length, from);
            }
            catch (SocketException) { /* ICMP port unreachable from a previous reply; keep listening */ }
        }
    }

    // ───────────────────────── fast UDP path ─────────────────────────

    private void UdpLoop()
    {
        using var sock = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        sock.Bind(new IPEndPoint(IPAddress.Any, UdpPort));
        var buf = new byte[64];
        var mac = new byte[32];
        EndPoint from = new IPEndPoint(IPAddress.Any, 0);
        while (true)
        {
            int n;
            try { n = sock.ReceiveFrom(buf, ref from); }
            catch (SocketException) { continue; }
            if (n != 26 || buf[0] != (byte)'P') continue;

            uint sid = BinaryPrimitives.ReadUInt32BigEndian(buf.AsSpan(2));
            if (!_sessions.TryGetValue(sid, out var s) || s.UdpKey == null) continue;


            HMACSHA256.HashData(s.UdpKey, buf.AsSpan(0, 18), mac);
            if (!CryptographicOperations.FixedTimeEquals(mac[..8], buf.AsSpan(18, 8))) continue;

            uint seq = BinaryPrimitives.ReadUInt32BigEndian(buf.AsSpan(6));
            if (seq <= s.LastSeq) continue; // replay or stale reorder
            s.LastSeq = seq;

            float dx = BinaryPrimitives.ReadSingleBigEndian(buf.AsSpan(10));
            float dy = BinaryPrimitives.ReadSingleBigEndian(buf.AsSpan(14));
            if (!float.IsFinite(dx) || !float.IsFinite(dy)) continue;
            switch (buf[1])
            {
                case 1: input.MoveBy(dx, dy); break;
                case 2: input.Scroll(dx, dy); break;
                case 3: laser.MoveBy(dx, dy); break;
            }
        }
    }

    // ───────────────────────── TCP control channel ─────────────────────────

    private async Task AcceptLoop()
    {
        var listener = new TcpListener(IPAddress.Any, TcpPort);
        listener.Start();
        while (true)
        {
            var client = await listener.AcceptTcpClientAsync();
            client.NoDelay = true;
            _ = Task.Run(() => Handle(client));
        }
    }

    private async Task Handle(TcpClient client)
    {
        Session? session = null;
        using var conn = client;
        var stream = client.GetStream();
        var writer = new LineWriter(stream);
        var nonce = Convert.ToHexString(RandomNumberGenerator.GetBytes(16));
        PairedDevice? device = null;
        ECDiffieHellman? ecdh = null;
        byte[]? shared = null;
        string? pin = null;
        PairingForm? pinForm = null;
        int failures = 0;
        var authDeadline = DateTime.UtcNow.AddMinutes(2);

        void ClosePin() { var f = pinForm; pinForm = null; if (f != null) ui.BeginInvoke(() => f.Close()); }

        try
        {
            var reader = new LineReader(stream);
            while (true)
            {
                using var cts = new CancellationTokenSource(session == null ? TimeSpan.FromMinutes(2) : TimeSpan.FromSeconds(12));
                var line = await reader.ReadLineAsync(cts.Token);
                if (line == null) break;
                if (session == null && DateTime.UtcNow > authDeadline) break;

                var msg = JsonNode.Parse(line)?.AsObject();
                var t = (string?)msg?["t"];
                if (msg == null || t == null) continue;

                if (session == null)
                {
                    switch (t)
                    {
                        case "hello":
                        {
                            var dev = (string?)msg["dev"] ?? "";
                            lock (config) device = config.Devices.FirstOrDefault(d => d.Id == dev)
                                ?? new PairedDevice { Id = dev, Name = Trunc((string?)msg["name"] ?? "Android", 40) };
                            bool paired = !string.IsNullOrEmpty(device.Key);
                            await writer.Send(new JsonObject
                            {
                                ["t"] = "welcome", ["id"] = config.ServerId, ["name"] = Environment.MachineName,
                                ["nonce"] = nonce, ["paired"] = paired,
                            });
                            break;
                        }
                        case "pair" when device != null:
                        {
                            ecdh?.Dispose();
                            ecdh = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
                            using var peer = ECDiffieHellman.Create();
                            peer.ImportSubjectPublicKeyInfo(Convert.FromBase64String((string)msg["pub"]!), out int _);
                            shared = ecdh.DeriveRawSecretAgreement(peer.PublicKey);
                            pin = RandomNumberGenerator.GetInt32(0, 10000).ToString("D4");
                            var name = device.Name;
                            ClosePin();
                            ui.Invoke(() =>
                            {
                                pinForm = new PairingForm(pin, name, () => client.Close());
                                pinForm.Show();
                                pinForm.Activate();
                            });
                            await writer.Send(new JsonObject
                            {
                                ["t"] = "pairPub", ["pub"] = Convert.ToBase64String(ecdh.ExportSubjectPublicKeyInfo()),
                            });
                            break;
                        }
                        case "pairProof" when device != null && shared != null && pin != null:
                        {
                            var expect = Hmac(shared, $"pin:{pin}:{nonce}");
                            var got = Convert.FromBase64String((string?)msg["proof"] ?? "");
                            if (!CryptographicOperations.FixedTimeEquals(expect, got))
                            {
                                await writer.Send(new JsonObject { ["t"] = "pairFail", ["left"] = 2 - failures });
                                if (++failures >= 3) return;
                                break;
                            }
                            device.Key = Convert.ToBase64String(Hmac(shared, $"key:{nonce}"));
                            lock (config)
                            {
                                config.Devices.RemoveAll(d => d.Id == device.Id);
                                config.Devices.Add(device);
                                config.Save();
                            }
                            ClosePin();
                            await writer.Send(new JsonObject { ["t"] = "paired" });
                            break;
                        }
                        case "auth" when device != null && !string.IsNullOrEmpty(device.Key):
                        {
                            var key = Convert.FromBase64String(device.Key);
                            var got = Convert.FromBase64String((string?)msg["proof"] ?? "");
                            if (!CryptographicOperations.FixedTimeEquals(Hmac(key, $"auth:{nonce}"), got))
                            {
                                await writer.Send(new JsonObject { ["t"] = "authFail" });
                                return;
                            }
                            session = new Session(device.Id, device.Name, writer, client, Hmac(key, $"udp:{nonce}"));
                            _sessions[session.Sid] = session;
                            SessionsChanged?.Invoke();
                            var v = audio.Get();
                            await writer.Send(new JsonObject
                            {
                                ["t"] = "ready", ["sid"] = session.Sid, ["udp"] = UdpPort, ["name"] = Environment.MachineName,
                                ["vol"] = v?.level ?? 0, ["mute"] = v?.muted ?? false, ["blur"] = blur.Visible,
                            });
                            break;
                        }
                    }
                    continue;
                }

                await Dispatch(session, t, msg);
            }
        }
        catch (Exception e) when (e is IOException or OperationCanceledException or SocketException
                                       or JsonException or FormatException or ObjectDisposedException
                                       or InvalidOperationException or CryptographicException)
        {
            // connection dropped or garbage; just end the session
        }
        finally
        {
            ClosePin();
            ecdh?.Dispose();
            if (session != null)
            {
                _sessions.TryRemove(session.Sid, out _);
                session.Gone.Cancel();
                if (session.LaserOn) laser.SetActive(false);
                SessionsChanged?.Invoke();
            }
        }
    }

    private async Task Dispatch(Session s, string t, JsonObject msg)
    {
        switch (t)
        {
            case "ping": await s.Writer.Send(new JsonObject { ["t"] = "pong" }); break;
            case "click": input.Button((string?)msg["b"] ?? "l", (string?)msg["a"] ?? "click"); break;
            case "key":
                input.Key((string?)msg["k"] ?? "", msg["mods"]?.AsArray().Select(m => (string?)m ?? "").ToList());
                break;
            case "combo": input.Combo((string?)msg["k"] ?? ""); break;
            case "text": input.Text(Trunc((string?)msg["s"] ?? "", 4096)); break;
            case "backspace": input.Backspace((int?)msg["n"] ?? 1); break;
            case "vol": audio.Set((float?)msg["v"] ?? 0); PushVolumeIfChanged(); break;
            case "mute": audio.ToggleMute(); PushVolumeIfChanged(); break;
            case "blur": ui.BeginInvoke(blur.Toggle); break;
            case "laser":
                s.LaserOn = (bool?)msg["on"] ?? false;
                laser.SetActive(s.LaserOn);
                break;
            case "windows":
                var list = new JsonArray();
                foreach (var w in windows.List())
                    list.Add(new JsonObject { ["id"] = w.Id, ["title"] = w.Title, ["app"] = w.App, ["icon"] = w.Icon });
                await s.Writer.Send(new JsonObject { ["t"] = "windows", ["list"] = list });
                break;
            case "focus": windows.Focus((long?)msg["id"] ?? 0); break;
            case "screen":
                await s.Writer.Send(AllowScreen
                    ? new JsonObject { ["t"] = "screen", ["port"] = ScreenStreamer.Port }
                    : new JsonObject { ["t"] = "screen", ["denied"] = true });
                break;
            case "cursorTo":
            {
                double nx = (double?)msg["x"] ?? 0.5, ny = (double?)msg["y"] ?? 0.5;
                int mx = (int?)msg["mx"] ?? 0, my = (int?)msg["my"] ?? 0, mw = (int?)msg["mw"] ?? 1, mh = (int?)msg["mh"] ?? 1;
                input.MoveTo(mx + (int)(Math.Clamp(nx, 0, 1) * mw), my + (int)(Math.Clamp(ny, 0, 1) * mh));
                break;
            }
        }
    }

    private void PushVolumeIfChanged()
    {
        if (_sessions.IsEmpty) return;
        var v = audio.Get();
        if (v == null || v == _lastVolume) return;
        _lastVolume = v;
        Broadcast(new JsonObject { ["t"] = "vol", ["v"] = v.Value.level, ["m"] = v.Value.muted });
    }

    private void Broadcast(JsonObject msg)
    {
        foreach (var s in _sessions.Values) _ = s.Writer.Send(msg.DeepClone().AsObject());
    }

    private static byte[] Hmac(byte[] key, string data) => HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(data));
    private static string Trunc(string s, int max) => s.Length <= max ? s : s[..max];

    private sealed class Session(string deviceId, string deviceName, LineWriter writer, TcpClient client, byte[] udpKey)
    {
        public uint Sid { get; } = (uint)RandomNumberGenerator.GetInt32(1, int.MaxValue);
        public string DeviceId => deviceId;
        public string DeviceName => deviceName;
        public LineWriter Writer => writer;
        public byte[] UdpKey => udpKey;
        public uint LastSeq;
        public bool LaserOn;
        public CancellationTokenSource Gone { get; } = new();
        public void Close() => client.Close();
    }

    private sealed class LineWriter(Stream stream)
    {
        private readonly SemaphoreSlim _gate = new(1, 1);

        public async Task Send(JsonObject msg)
        {
            var bytes = Encoding.UTF8.GetBytes(msg.ToJsonString() + "\n");
            await _gate.WaitAsync();
            try { await stream.WriteAsync(bytes); }
            catch { /* reader side will notice the drop */ }
            finally { _gate.Release(); }
        }
    }

    private sealed class LineReader(Stream stream)
    {
        private readonly byte[] _buf = new byte[8192];
        private int _start, _end;
        private readonly MemoryStream _line = new();

        public async Task<string?> ReadLineAsync(CancellationToken ct)
        {
            while (true)
            {
                for (int i = _start; i < _end; i++)
                {
                    if (_buf[i] != (byte)'\n') continue;
                    _line.Write(_buf, _start, i - _start);
                    _start = i + 1;
                    var s = Encoding.UTF8.GetString(_line.GetBuffer(), 0, (int)_line.Length);
                    _line.SetLength(0);
                    return s;
                }
                _line.Write(_buf, _start, _end - _start);
                if (_line.Length > MaxLine) throw new InvalidOperationException("line too long");
                _start = 0;
                _end = await stream.ReadAsync(_buf, ct);
                if (_end == 0) return null;
            }
        }
    }
}
