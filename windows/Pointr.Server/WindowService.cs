using System.Diagnostics;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Text;
using static Pointr.Native;

namespace Pointr;

internal sealed record WindowInfo(long Id, string Title, string App, string? Icon);

/// <summary>Lists Alt-Tab-style top-level windows and brings one to the foreground.</summary>
internal sealed class WindowService(InputService input)
{
    private const int IconSize = 96;
    private readonly Dictionary<string, string?> _iconCache = new();
    private readonly Dictionary<IntPtr, long> _firstSeen = new();
    private long _counter;
    private readonly int _ownPid = Environment.ProcessId;

    public List<WindowInfo> List()
    {
        var handles = new List<IntPtr>();
        EnumWindows((h, _) => { if (IsAppWindow(h)) handles.Add(h); return true; }, IntPtr.Zero);

        lock (_iconCache)
        {
            foreach (var dead in _firstSeen.Keys.Where(k => !handles.Contains(k)).ToList()) _firstSeen.Remove(dead);
            foreach (var h in handles) if (!_firstSeen.ContainsKey(h)) _firstSeen[h] = _counter++;

            // Taskbar-like stable order: windows keep the slot in which they first appeared.
            return handles.OrderBy(h => _firstSeen[h]).Select(Describe).ToList();
        }
    }

    public void Focus(long id)
    {
        var h = new IntPtr(id);
        if (!IsWindow(h)) return;
        if (IsIconic(h)) ShowWindow(h, SW_RESTORE);
        input.TapAlt(); // satisfies the foreground-lock rules for background processes
        SetForegroundWindow(h);
    }

    private bool IsAppWindow(IntPtr h)
    {
        if (!IsWindowVisible(h) || GetWindow(h, GW_OWNER) != IntPtr.Zero) return false;
        long ex = GetWindowLongPtr(h, GWL_EXSTYLE).ToInt64();
        if ((ex & WS_EX_TOOLWINDOW) != 0 && (ex & WS_EX_APPWINDOW) == 0) return false;
        if ((ex & WS_EX_NOACTIVATE) != 0) return false;
        if (GetWindowTextLength(h) == 0) return false;
        if (DwmGetWindowAttribute(h, DWMWA_CLOAKED, out int cloaked, sizeof(int)) == 0 && cloaked != 0) return false;
        GetWindowThreadProcessId(h, out var pid);
        return pid != _ownPid;
    }

    private WindowInfo Describe(IntPtr h)
    {
        var sb = new StringBuilder(GetWindowTextLength(h) + 1);
        GetWindowText(h, sb, sb.Capacity);
        GetWindowThreadProcessId(h, out var pid);
        var path = ProcessPath(pid);

        string app = "";
        string? icon = null;
        if (path != null)
        {
            try { app = FileVersionInfo.GetVersionInfo(path).FileDescription ?? ""; } catch { }
            if (string.IsNullOrWhiteSpace(app)) app = Path.GetFileNameWithoutExtension(path);
            // UWP apps all live in ApplicationFrameHost; their real icon is only on the window.
            bool frameHost = path.EndsWith("ApplicationFrameHost.exe", StringComparison.OrdinalIgnoreCase);
            icon = frameHost ? WindowIcon(h) : ExeIcon(path) ?? WindowIcon(h);
        }
        return new WindowInfo(h.ToInt64(), sb.ToString(), app, icon);
    }

    private string? ExeIcon(string path)
    {
        if (_iconCache.TryGetValue(path, out var cached)) return cached;
        string? result = null;
        try
        {
            using var ic = Icon.ExtractIcon(path, 0, IconSize);
            if (ic != null) result = ToPng(ic);
        }
        catch { }
        _iconCache[path] = result;
        return result;
    }

    private static string? WindowIcon(IntPtr h)
    {
        SendMessageTimeout(h, WM_GETICON, new IntPtr(1) /* ICON_BIG */, IntPtr.Zero, SMTO_ABORTIFHUNG, 100, out var hIcon);
        if (hIcon == IntPtr.Zero) hIcon = GetClassLongPtr(h, GCLP_HICON);
        if (hIcon == IntPtr.Zero) return null;
        try { using var ic = (Icon)Icon.FromHandle(hIcon).Clone(); return ToPng(ic); }
        catch { return null; }
    }

    private static string ToPng(Icon icon)
    {
        using var src = icon.ToBitmap();
        using var bmp = new Bitmap(IconSize, IconSize, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(bmp))
        {
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
            g.DrawImage(src, 0, 0, IconSize, IconSize);
        }
        using var ms = new MemoryStream();
        bmp.Save(ms, ImageFormat.Png);
        return Convert.ToBase64String(ms.ToArray());
    }
}
