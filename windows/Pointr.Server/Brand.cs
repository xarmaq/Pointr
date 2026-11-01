using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Runtime.InteropServices;

namespace Pointr;

/// <summary>The phone app's look on the PC: colours, Unbounded/Onest, the dot field.</summary>
internal static class Brand
{
    public static readonly Color Bg = Color.FromArgb(6, 8, 11);
    public static readonly Color Panel = Color.FromArgb(17, 22, 30);
    public static readonly Color Cell = Color.FromArgb(23, 28, 37);
    public static readonly Color Edge = Color.FromArgb(39, 46, 58);
    public static readonly Color Text = Color.FromArgb(242, 245, 250);
    public static readonly Color Muted = Color.FromArgb(138, 147, 163);
    public static readonly Color Accent = Color.FromArgb(76, 141, 255);

    private static readonly PrivateFontCollection Fonts = new();
    public static readonly FontFamily Display;
    public static readonly FontFamily Ui;

    static Brand()
    {
        Load("Pointr.unbounded.ttf");
        Load("Pointr.onest.ttf");
        Display = Fonts.Families.FirstOrDefault(f => f.Name.StartsWith("Unbounded")) ?? new FontFamily("Segoe UI Semibold");
        Ui = Fonts.Families.FirstOrDefault(f => f.Name.StartsWith("Onest")) ?? new FontFamily("Segoe UI");
    }

    private static void Load(string resource)
    {
        using var s = typeof(Brand).Assembly.GetManifestResourceStream(resource);
        if (s == null) return;
        var data = new byte[s.Length];
        s.ReadExactly(data);
        // GDI+ and GDI (used by WinForms controls) both read from this block for the life
        // of the process, so it is intentionally never freed.
        var mem = Marshal.AllocCoTaskMem(data.Length);
        Marshal.Copy(data, 0, mem, data.Length);
        Fonts.AddMemoryFont(mem, data.Length);
        uint installed = 0;
        AddFontMemResourceEx(mem, (uint)data.Length, IntPtr.Zero, ref installed);
    }

    /// <summary>The trackpad's dot grid, glowing around <paramref name="focus"/>.</summary>
    public static void DotField(Graphics g, Rectangle area, PointF focus, float scale, Color glow)
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        float reach = 150 * scale;
        using (var path = new GraphicsPath())
        {
            path.AddEllipse(focus.X - reach * 1.3f, focus.Y - reach * 1.3f, reach * 2.6f, reach * 2.6f);
            using var halo = new PathGradientBrush(path) { CenterColor = Color.FromArgb(46, glow), SurroundColors = [Color.FromArgb(0, glow)] };
            g.FillPath(halo, path);
        }
        float step = 17 * scale;
        for (float y = area.Top + step / 2; y < area.Bottom; y += step)
        for (float x = area.Left + step / 2; x < area.Right; x += step)
        {
            float d = MathF.Sqrt((x - focus.X) * (x - focus.X) + (y - focus.Y) * (y - focus.Y));
            float k = d < reach ? (1 - d / reach) * (1 - d / reach) : 0;
            float r = (1.1f + 1.6f * k) * scale;
            int a = (int)(26 + 200 * k);
            var c = Color.FromArgb(a, Lerp(Color.White, glow, k));
            using var b = new SolidBrush(c);
            g.FillEllipse(b, x - r, y - r, 2 * r, 2 * r);
        }
    }

    public static Color Lerp(Color a, Color b, float t) => Color.FromArgb(
        (int)(a.R + (b.R - a.R) * t), (int)(a.G + (b.G - a.G) * t), (int)(a.B + (b.B - a.B) * t));

    public static GraphicsPath Rounded(RectangleF r, float rad)
    {
        var p = new GraphicsPath();
        float d = rad * 2;
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    /// <summary>Dark title bar to match (Windows 10 2004+ / 11).</summary>
    public static void DarkTitleBar(IntPtr hwnd)
    {
        int on = 1;
        DwmSetWindowAttribute(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */, ref on, sizeof(int));
        int caption = ColorTranslator.ToWin32(Bg);
        DwmSetWindowAttribute(hwnd, 35 /* DWMWA_CAPTION_COLOR (Win11) */, ref caption, sizeof(int));
    }

    [DllImport("gdi32.dll")] private static extern IntPtr AddFontMemResourceEx(IntPtr pbFont, uint cbFont, IntPtr pdv, ref uint pcFonts);
    [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
}

/// <summary>Tray menu in the app's dark palette.</summary>
internal sealed class DarkMenuRenderer : ToolStripProfessionalRenderer
{
    public DarkMenuRenderer() : base(new Colors()) { RoundedEdges = true; }

    protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
    {
        e.TextColor = e.Item.Enabled ? Brand.Text : Brand.Muted;
        base.OnRenderItemText(e);
    }

    protected override void OnRenderArrow(ToolStripArrowRenderEventArgs e)
    {
        e.ArrowColor = Brand.Muted;
        base.OnRenderArrow(e);
    }

    protected override void OnRenderItemCheck(ToolStripItemImageRenderEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var r = e.ImageRectangle;
        r.Inflate(-1, -1);
        using var b = new SolidBrush(Brand.Accent);
        g.FillEllipse(b, r);
        using var pen = new Pen(Color.White, Math.Max(1.5f, r.Width / 9f)) { StartCap = LineCap.Round, EndCap = LineCap.Round };
        g.DrawLines(pen, [new PointF(r.Left + r.Width * 0.28f, r.Top + r.Height * 0.52f),
            new PointF(r.Left + r.Width * 0.44f, r.Top + r.Height * 0.68f), new PointF(r.Left + r.Width * 0.73f, r.Top + r.Height * 0.36f)]);
    }

    private sealed class Colors : ProfessionalColorTable
    {
        private static readonly Color Hover = Color.FromArgb(28, 35, 47);
        public override Color ToolStripDropDownBackground => Brand.Panel;
        public override Color ImageMarginGradientBegin => Brand.Panel;
        public override Color ImageMarginGradientMiddle => Brand.Panel;
        public override Color ImageMarginGradientEnd => Brand.Panel;
        public override Color MenuBorder => Brand.Edge;
        public override Color MenuItemBorder => Hover;
        public override Color MenuItemSelected => Hover;
        public override Color MenuItemSelectedGradientBegin => Hover;
        public override Color MenuItemSelectedGradientEnd => Hover;
        public override Color MenuItemPressedGradientBegin => Hover;
        public override Color MenuItemPressedGradientEnd => Hover;
        public override Color SeparatorDark => Brand.Edge;
        public override Color SeparatorLight => Brand.Panel;
        public override Color CheckBackground => Brand.Panel;
        public override Color CheckSelectedBackground => Hover;
        public override Color CheckPressedBackground => Hover;
    }
}
