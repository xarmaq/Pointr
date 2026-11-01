using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using static Pointr.Native;

namespace Pointr;

/// <summary>A glowing red dot drawn above everything, click-through, driven by relative deltas.</summary>
internal sealed class LaserOverlay : Form
{
    private const int SpriteSize = 72;
    private readonly Control _ui;
    private double _x, _y;
    private bool _active, _movePending;
    private byte _alpha;
    private readonly System.Windows.Forms.Timer _fade = new() { Interval = 15 };

    public LaserOverlay(Control ui)
    {
        _ui = ui;
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        StartPosition = FormStartPosition.Manual;
        _fade.Tick += (_, _) => StepFade();
    }

    protected override bool ShowWithoutActivation => true;

    protected override CreateParams CreateParams
    {
        get
        {
            var cp = base.CreateParams;
            cp.ExStyle |= WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOPMOST | 0x80 /* TOOLWINDOW */ | 0x08000000 /* NOACTIVATE */;
            return cp;
        }
    }

    /// <summary>Thread-safe. Shows the dot at the cursor's screen centre, or hides it.</summary>
    public void SetActive(bool on) => _ui.BeginInvoke(() =>
    {
        if (on == _active) return;
        _active = on;
        if (on)
        {
            GetCursorPos(out var p);
            var b = Screen.FromPoint(new Point(p.X, p.Y)).Bounds;
            if (_alpha == 0) { _x = b.X + b.Width / 2.0; _y = b.Y + b.Height / 2.0; }
            if (!Visible) Show();
            Render();
        }
        _fade.Start();
    });

    /// <summary>Thread-safe; called at input rate, so UI updates are coalesced.</summary>
    public void MoveBy(double dx, double dy)
    {
        lock (this)
        {
            int vx = GetSystemMetrics(SM_XVIRTUALSCREEN), vy = GetSystemMetrics(SM_YVIRTUALSCREEN);
            _x = Math.Clamp(_x + dx, vx, vx + GetSystemMetrics(SM_CXVIRTUALSCREEN) - 1);
            _y = Math.Clamp(_y + dy, vy, vy + GetSystemMetrics(SM_CYVIRTUALSCREEN) - 1);
            if (_movePending) return;
            _movePending = true;
        }
        _ui.BeginInvoke(() =>
        {
            lock (this) _movePending = false;
            if (Visible) Render();
        });
    }

    private void StepFade()
    {
        int target = _active ? 255 : 0;
        int next = _alpha + (_active ? 40 : -25);
        _alpha = (byte)Math.Clamp(next, 0, 255);
        Render();
        if (_alpha != target) return;
        _fade.Stop();
        if (!_active) Hide();
    }

    private Bitmap? _sprite;

    private void Render()
    {
        _sprite ??= DrawSprite();
        var screenDc = GetDC(IntPtr.Zero);
        var memDc = CreateCompatibleDC(screenDc);
        var hBmp = _sprite.GetHbitmap(Color.FromArgb(0));
        var old = SelectObject(memDc, hBmp);
        try
        {
            var size = new SIZE(SpriteSize, SpriteSize);
            var src = new POINT(0, 0);
            var dst = new POINT((int)_x - SpriteSize / 2, (int)_y - SpriteSize / 2);
            var blend = new BLENDFUNCTION { BlendOp = 0, SourceConstantAlpha = _alpha, AlphaFormat = 1 /* AC_SRC_ALPHA */ };
            UpdateLayeredWindow(Handle, screenDc, ref dst, ref size, memDc, ref src, 0, ref blend, 2 /* ULW_ALPHA */);
        }
        finally
        {
            SelectObject(memDc, old);
            DeleteObject(hBmp);
            DeleteDC(memDc);
            ReleaseDC(IntPtr.Zero, screenDc);
        }
    }

    private static Bitmap DrawSprite()
    {
        var bmp = new Bitmap(SpriteSize, SpriteSize, PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(bmp);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        float c = SpriteSize / 2f;

        // soft halo
        using (var path = new GraphicsPath())
        {
            path.AddEllipse(0, 0, SpriteSize, SpriteSize);
            using var halo = new PathGradientBrush(path)
            {
                CenterColor = Color.FromArgb(150, 255, 40, 40),
                SurroundColors = [Color.FromArgb(0, 255, 0, 0)],
            };
            g.FillEllipse(halo, 0, 0, SpriteSize, SpriteSize);
        }
        // hot core
        const float core = 16f;
        using (var path = new GraphicsPath())
        {
            path.AddEllipse(c - core / 2, c - core / 2, core, core);
            using var brush = new PathGradientBrush(path)
            {
                CenterColor = Color.FromArgb(255, 255, 235, 235),
                SurroundColors = [Color.FromArgb(255, 255, 30, 40)],
            };
            g.FillEllipse(brush, c - core / 2, c - core / 2, core, core);
        }
        return bmp;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) { _fade.Dispose(); _sprite?.Dispose(); }
        base.Dispose(disposing);
    }
}
