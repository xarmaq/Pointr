using System.Drawing.Drawing2D;
using System.Drawing.Imaging;

namespace Pointr;

/// <summary>Covers every monitor with a frosted snapshot of itself, hiding whatever is on screen.</summary>
internal sealed class BlurOverlay
{
    private readonly List<Form> _forms = [];
    public bool Visible => _forms.Count > 0;

    /// <summary>Raised on the UI thread whenever the overlay appears or goes away.</summary>
    public event Action<bool>? Changed;

    /// <summary>Must run on the UI thread.</summary>
    public void Toggle()
    {
        if (Visible) Hide(); else Show();
    }

    private void Show()
    {
        foreach (var screen in Screen.AllScreens)
        {
            var b = screen.Bounds;
            using var shot = new Bitmap(b.Width, b.Height, PixelFormat.Format32bppRgb);
            using (var g = Graphics.FromImage(shot)) g.CopyFromScreen(b.Location, Point.Empty, b.Size);
            var form = new OverlayForm(Blur(shot)) { Bounds = b };
            form.Click += (_, _) => { if (Visible) Hide(); }; // escape hatch if the phone is gone
            _forms.Add(form);
            form.Show();
            form.SetBounds(b.X, b.Y, b.Width, b.Height);
            Fade(form, 0, 1, null);
        }
        Changed?.Invoke(true);
    }

    public void Hide()
    {
        foreach (var f in _forms.ToList()) Fade(f, 1, 0, () => { f.Close(); f.Dispose(); });
        _forms.Clear();
        Changed?.Invoke(false);
    }

    private static void Fade(Form f, double from, double to, Action? done)
    {
        const int steps = 12;
        int i = 0;
        f.Opacity = from;
        var t = new System.Windows.Forms.Timer { Interval = 15 };
        t.Tick += (_, _) =>
        {
            i++;
            double k = i / (double)steps;
            k = 1 - Math.Pow(1 - k, 3); // ease-out
            f.Opacity = from + (to - from) * k;
            if (i < steps) return;
            t.Stop(); t.Dispose();
            done?.Invoke();
        };
        t.Start();
    }

    /// <summary>Cheap but heavy blur: cascade down to ~1/24 size and back up with bicubic filtering.</summary>
    private static Bitmap Blur(Bitmap src)
    {
        Bitmap cur = src;
        int w = src.Width, h = src.Height;
        var owned = new List<Bitmap>();
        foreach (var f in new[] { 4, 14, 40 })
        {
            var next = Resize(cur, Math.Max(1, w / f), Math.Max(1, h / f));
            owned.Add(next);
            cur = next;
        }
        foreach (var f in new[] { 12, 3, 1 })
        {
            var next = Resize(cur, Math.Max(1, w / f), Math.Max(1, h / f));
            owned.Add(next);
            cur = next;
        }
        using (var g = Graphics.FromImage(cur))
        using (var tint = new SolidBrush(Color.FromArgb(70, 8, 10, 16)))
            g.FillRectangle(tint, 0, 0, cur.Width, cur.Height);
        foreach (var b in owned) if (b != cur) b.Dispose();
        return cur;
    }

    private static Bitmap Resize(Bitmap src, int w, int h)
    {
        var dst = new Bitmap(w, h, PixelFormat.Format32bppRgb);
        using var g = Graphics.FromImage(dst);
        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        using var attrs = new ImageAttributes();
        attrs.SetWrapMode(WrapMode.TileFlipXY); // no dark fringe at the edges
        g.DrawImage(src, new Rectangle(0, 0, w, h), 0, 0, src.Width, src.Height, GraphicsUnit.Pixel, attrs);
        return dst;
    }

    private sealed class OverlayForm : Form
    {
        private readonly Bitmap _image;

        public OverlayForm(Bitmap image)
        {
            _image = image;
            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.Manual;
            ShowInTaskbar = false;
            TopMost = true;
            BackColor = Color.Black;
            DoubleBuffered = true;
        }

        protected override bool ShowWithoutActivation => true;

        protected override CreateParams CreateParams
        {
            get
            {
                var cp = base.CreateParams;
                cp.ExStyle |= 0x08000000 /* NOACTIVATE */ | 0x80 /* TOOLWINDOW */;
                return cp;
            }
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.InterpolationMode = InterpolationMode.NearestNeighbor;
            e.Graphics.DrawImage(_image, ClientRectangle);
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) _image.Dispose();
            base.Dispose(disposing);
        }
    }
}
