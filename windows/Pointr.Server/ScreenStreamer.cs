using System.Buffers.Binary;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using static Pointr.Native;

namespace Pointr;

/// <summary>
/// Live view of the monitor under the cursor, for the phone to draw beneath its trackpad.
/// TCP 47803. The phone opens it with one JSON line {"sid","proof"} (proof = HMAC(session UDP
/// key, "screen:"+sid)); after that the server writes binary messages:
///   'C' float nx, float ny                                    — cursor, 0..1 within the monitor
///   'F' int x, int y, int w, int h, int len, byte[len] jpeg  — a frame of that monitor
/// and the phone answers every frame with one byte once it has drawn it. Waiting for that ack
/// is the flow control: a slow network or phone simply gets fewer frames.
/// </summary>
internal sealed class ScreenStreamer(Func<uint, byte[], CancellationToken?> authorize)
{
    public const int Port = 47803;
    private const int MaxWidth = 960, MinFrameMs = 50; // ≤ 20 fps
    private static readonly ImageCodecInfo Jpeg = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);

    private int _active;
    public bool Streaming => _active > 0;
    public event Action? StreamingChanged;

    public void Start() => _ = AcceptLoop();

    private async Task AcceptLoop()
    {
        var listener = new TcpListener(IPAddress.Any, Port);
        listener.Start();
        while (true)
        {
            var client = await listener.AcceptTcpClientAsync();
            client.NoDelay = true;
            new Thread(() => Serve(client)) { IsBackground = true, Name = "screen" }.Start();
        }
    }

    private void Serve(TcpClient client)
    {
        using var _ = client;
        var stream = client.GetStream();
        client.ReceiveTimeout = 5000;
        CancellationToken session;
        try
        {
            var line = ReadLine(stream);
            var msg = JsonNode.Parse(line)!.AsObject();
            var sid = (uint)(long)msg["sid"]!;
            var proof = Convert.FromBase64String((string)msg["proof"]!);
            if (authorize(sid, proof) is not { } token) return;
            session = token;
        }
        catch { return; }

        Interlocked.Increment(ref _active);
        StreamingChanged?.Invoke();
        try { Loop(client, stream, session); }
        catch { /* phone went away */ }
        finally
        {
            Interlocked.Decrement(ref _active);
            StreamingChanged?.Invoke();
        }
    }

    private static void Loop(TcpClient client, NetworkStream stream, CancellationToken session)
    {
        client.ReceiveTimeout = 0;
        int acksPending = 0;
        var ackReader = new Thread(() =>
        {
            try { while (stream.ReadByte() >= 0) Interlocked.Decrement(ref acksPending); }
            catch { }
            client.Close();
        }) { IsBackground = true };
        ackReader.Start();

        var cursorMsg = new byte[9];
        Rectangle lastMon = Rectangle.Empty;
        long lastHash = 0;
        Point lastCursor = new(int.MinValue, 0);
        long lastFrame = 0;
        Bitmap? full = null, small = null;
        try
        {
            while (client.Connected && !session.IsCancellationRequested)
            {
                GetCursorPos(out var p);
                var mon = Screen.FromPoint(new Point(p.X, p.Y)).Bounds;

                if (p.X != lastCursor.X || p.Y != lastCursor.Y)
                {
                    lastCursor = new Point(p.X, p.Y);
                    cursorMsg[0] = (byte)'C';
                    BinaryPrimitives.WriteSingleBigEndian(cursorMsg.AsSpan(1), (p.X - mon.X) / (float)mon.Width);
                    BinaryPrimitives.WriteSingleBigEndian(cursorMsg.AsSpan(5), (p.Y - mon.Y) / (float)mon.Height);
                    stream.Write(cursorMsg);
                }

                long now = Environment.TickCount64;
                if (Volatile.Read(ref acksPending) <= 0 && now - lastFrame >= MinFrameMs)
                {
                    if (full == null || full.Size != mon.Size)
                    {
                        full?.Dispose(); small?.Dispose();
                        full = new Bitmap(mon.Width, mon.Height, PixelFormat.Format32bppRgb);
                        int w = Math.Min(MaxWidth, mon.Width);
                        small = new Bitmap(w, Math.Max(1, mon.Height * w / mon.Width), PixelFormat.Format24bppRgb);
                    }
                    using (var g = Graphics.FromImage(full)) g.CopyFromScreen(mon.Location, Point.Empty, mon.Size);
                    using (var g = Graphics.FromImage(small!))
                    {
                        g.InterpolationMode = InterpolationMode.Bilinear;
                        g.DrawImage(full, 0, 0, small!.Width, small.Height);
                    }
                    long hash = Fingerprint(small!);
                    lastFrame = now;
                    if (hash != lastHash || mon != lastMon)
                    {
                        lastHash = hash;
                        lastMon = mon;
                        SendFrame(stream, small!, mon);
                        Interlocked.Increment(ref acksPending);
                    }
                }
                Thread.Sleep(16); // cursor updates at ~60 Hz
            }
        }
        finally
        {
            full?.Dispose();
            small?.Dispose();
        }
    }

    private static void SendFrame(NetworkStream stream, Bitmap bmp, Rectangle mon)
    {
        using var ms = new MemoryStream();
        ms.Write(new byte[21]); // header placeholder
        using (var ep = new EncoderParameters(1))
        {
            ep.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 60L);
            bmp.Save(ms, Jpeg, ep);
        }
        var buf = ms.GetBuffer();
        buf[0] = (byte)'F';
        BinaryPrimitives.WriteInt32BigEndian(buf.AsSpan(1), mon.X);
        BinaryPrimitives.WriteInt32BigEndian(buf.AsSpan(5), mon.Y);
        BinaryPrimitives.WriteInt32BigEndian(buf.AsSpan(9), mon.Width);
        BinaryPrimitives.WriteInt32BigEndian(buf.AsSpan(13), mon.Height);
        BinaryPrimitives.WriteInt32BigEndian(buf.AsSpan(17), (int)ms.Length - 21);
        stream.Write(buf, 0, (int)ms.Length);
    }

    /// <summary>Cheap change detector: samples a sparse grid of pixels.</summary>
    private static unsafe long Fingerprint(Bitmap bmp)
    {
        var data = bmp.LockBits(new Rectangle(0, 0, bmp.Width, bmp.Height), ImageLockMode.ReadOnly, PixelFormat.Format24bppRgb);
        try
        {
            long h = 1469598103934665603;
            byte* p = (byte*)data.Scan0;
            for (int y = 0; y < bmp.Height; y += 3)
            for (int x = 0; x < bmp.Width; x += 5)
            {
                byte* px = p + y * data.Stride + x * 3;
                h = (h ^ (px[0] | px[1] << 8 | px[2] << 16)) * 1099511628211;
            }
            return h;
        }
        finally { bmp.UnlockBits(data); }
    }

    private static string ReadLine(Stream s)
    {
        var sb = new StringBuilder();
        while (sb.Length < 512)
        {
            int b = s.ReadByte();
            if (b < 0 || b == '\n') break;
            sb.Append((char)b);
        }
        return sb.ToString();
    }

    public static byte[] Proof(byte[] udpKey, uint sid) => HMACSHA256.HashData(udpKey, Encoding.UTF8.GetBytes($"screen:{sid}"));
}
