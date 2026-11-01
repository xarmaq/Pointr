using System.Runtime.InteropServices;
using static Pointr.Native;

namespace Pointr;

/// <summary>Synthesizes mouse and keyboard input via SendInput.</summary>
internal sealed class InputService
{
    private readonly object _lock = new();
    private double _fracX, _fracY, _wheelX, _wheelY;
    private POINT _target;
    private long _lastMove;

    private static readonly Dictionary<string, ushort> Keys = new()
    {
        ["left"] = 0x25, ["up"] = 0x26, ["right"] = 0x27, ["down"] = 0x28,
        ["enter"] = 0x0D, ["esc"] = 0x1B, ["tab"] = 0x09, ["backspace"] = 0x08,
        ["delete"] = 0x2E, ["home"] = 0x24, ["end"] = 0x23, ["pgup"] = 0x21, ["pgdn"] = 0x22,
        ["space"] = 0x20, ["f5"] = 0x74, ["win"] = 0x5B,
        ["ctrl"] = 0x11, ["alt"] = 0x12, ["shift"] = 0x10,
        ["volup"] = 0xAF, ["voldown"] = 0xAE, ["mute"] = 0xAD,
    };

    private static readonly HashSet<ushort> Extended = [0x25, 0x26, 0x27, 0x28, 0x2E, 0x24, 0x23, 0x21, 0x22, 0x5B];

    public void MoveBy(double dx, double dy)
    {
        lock (_lock)
        {
            _fracX += dx; _fracY += dy;
            int ix = (int)Math.Truncate(_fracX), iy = (int)Math.Truncate(_fracY);
            if (ix == 0 && iy == 0) return;
            _fracX -= ix; _fracY -= iy;

            // Injected moves land asynchronously, so GetCursorPos lags behind a fast stream of deltas.
            // While a stream is running trust our own target; re-sync once it pauses.
            long now = Environment.TickCount64;
            POINT p;
            if (now - _lastMove < 60) p = _target;
            else GetCursorPos(out p);
            _lastMove = now;
            int vx = GetSystemMetrics(SM_XVIRTUALSCREEN), vy = GetSystemMetrics(SM_YVIRTUALSCREEN);
            int vw = GetSystemMetrics(SM_CXVIRTUALSCREEN), vh = GetSystemMetrics(SM_CYVIRTUALSCREEN);
            int x = Math.Clamp(p.X + ix, vx, vx + vw - 1);
            int y = Math.Clamp(p.Y + iy, vy, vy + vh - 1);
            _target = new POINT(x, y);
            // Absolute coordinates avoid Windows' own pointer acceleration stacking on top of ours.
            var input = Mouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, 0,
                (int)Math.Round((x - vx) * 65535.0 / (vw - 1)), (int)Math.Round((y - vy) * 65535.0 / (vh - 1)));
            Send(input);
        }
    }

    /// <summary>Puts the cursor at an absolute virtual-desktop point (the phone's "jump here").</summary>
    public void MoveTo(int x, int y)
    {
        lock (_lock)
        {
            int vx = GetSystemMetrics(SM_XVIRTUALSCREEN), vy = GetSystemMetrics(SM_YVIRTUALSCREEN);
            int vw = GetSystemMetrics(SM_CXVIRTUALSCREEN), vh = GetSystemMetrics(SM_CYVIRTUALSCREEN);
            x = Math.Clamp(x, vx, vx + vw - 1);
            y = Math.Clamp(y, vy, vy + vh - 1);
            _target = new POINT(x, y);
            _lastMove = Environment.TickCount64;
            _fracX = _fracY = 0;
            Send(Mouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, 0,
                (int)Math.Round((x - vx) * 65535.0 / (vw - 1)), (int)Math.Round((y - vy) * 65535.0 / (vh - 1))));
        }
    }

    /// <summary>Scroll in wheel units, 120 = one notch. Fractions accumulate.</summary>
    public void Scroll(double dx, double dy)
    {
        lock (_lock)
        {
            _wheelX += dx; _wheelY += dy;
            int iy = (int)Math.Truncate(_wheelY), ix = (int)Math.Truncate(_wheelX);
            _wheelY -= iy; _wheelX -= ix;
            if (iy != 0) Send(Mouse(MOUSEEVENTF_WHEEL, iy));
            if (ix != 0) Send(Mouse(MOUSEEVENTF_HWHEEL, ix));
        }
    }

    public void Button(string button, string action)
    {
        (uint down, uint up) = button switch
        {
            "r" => (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
            "m" => (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
            _ => (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
        };
        switch (action)
        {
            case "down": Send(Mouse(down)); break;
            case "up": Send(Mouse(up)); break;
            case "double": Send(Mouse(down), Mouse(up), Mouse(down), Mouse(up)); break;
            default: Send(Mouse(down), Mouse(up)); break;
        }
    }

    /// <summary>Presses a named key, optionally with modifiers held.</summary>
    public void Key(string name, IReadOnlyList<string>? mods = null)
    {
        if (!Keys.TryGetValue(name, out var vk))
        {
            // single letters/digits map straight to their virtual-key codes (for Ctrl+C and friends)
            if (name.Length != 1 || !char.IsAsciiLetterOrDigit(name[0])) return;
            vk = char.ToUpperInvariant(name[0]);
        }
        var list = new List<INPUT>();
        var modVks = (mods ?? []).Where(Keys.ContainsKey).Select(m => Keys[m]).ToList();
        foreach (var m in modVks) list.Add(Kbd(m, false));
        list.Add(Kbd(vk, false));
        list.Add(Kbd(vk, true));
        for (int i = modVks.Count - 1; i >= 0; i--) list.Add(Kbd(modVks[i], true));
        Send([.. list]);
    }

    public void Combo(string combo)
    {
        switch (combo)
        {
            case "alttab": Key("tab", ["alt"]); break;
            case "altshifttab": Key("tab", ["alt", "shift"]); break;
            case "taskview": Key("tab", ["win"]); break;
            case "desktop": KeyVk(0x44 /* D */, 0x5B /* Win */); break;
        }
    }

    private void KeyVk(ushort vk, ushort mod) =>
        Send(Kbd(mod, false), Kbd(vk, false), Kbd(vk, true), Kbd(mod, true));

    /// <summary>Types arbitrary Unicode text (Cyrillic, emoji) regardless of the PC keyboard layout.</summary>
    public void Text(string s)
    {
        var list = new List<INPUT>();
        foreach (var c in s.Replace("\r\n", "\n"))
        {
            if (c == '\n') { list.Add(Kbd(0x0D, false)); list.Add(Kbd(0x0D, true)); continue; }
            list.Add(Unicode(c, false));
            list.Add(Unicode(c, true));
        }
        if (list.Count > 0) Send([.. list]);
    }

    public void Backspace(int n)
    {
        var list = new List<INPUT>();
        for (int i = 0; i < Math.Clamp(n, 0, 500); i++) { list.Add(Kbd(0x08, false)); list.Add(Kbd(0x08, true)); }
        if (list.Count > 0) Send([.. list]);
    }

    /// <summary>A bare Alt tap; lets a background process call SetForegroundWindow.</summary>
    public void TapAlt() => Send(Kbd(0x12, false), Kbd(0x12, true));

    private static INPUT Mouse(uint flags, int data = 0, int dx = 0, int dy = 0) => new()
    {
        type = INPUT_MOUSE,
        U = new InputUnion { mi = new MOUSEINPUT { dx = dx, dy = dy, mouseData = data, dwFlags = flags } }
    };

    private static INPUT Kbd(ushort vk, bool up) => new()
    {
        type = INPUT_KEYBOARD,
        U = new InputUnion { ki = new KEYBDINPUT {
            wVk = vk,
            dwFlags = (up ? KEYEVENTF_KEYUP : 0) | (Extended.Contains(vk) ? KEYEVENTF_EXTENDEDKEY : 0) } }
    };

    private static INPUT Unicode(char c, bool up) => new()
    {
        type = INPUT_KEYBOARD,
        U = new InputUnion { ki = new KEYBDINPUT { wScan = c, dwFlags = KEYEVENTF_UNICODE | (up ? KEYEVENTF_KEYUP : 0) } }
    };

    private static void Send(params INPUT[] inputs) =>
        SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
}
