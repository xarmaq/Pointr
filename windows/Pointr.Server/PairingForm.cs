using System.Drawing.Drawing2D;
using System.Drawing.Text;

namespace Pointr;

/// <summary>Shows the one-time PIN the phone must enter to pair, in the phone app's style.</summary>
internal sealed class PairingForm : Form
{
    private const int W = 460, H = 350;
    private readonly string _pin, _device;
    private readonly Action _onCancel;
    private RectangleF _cancel;
    private bool _cancelHover;

    public PairingForm(string pin, string device, Action onCancel)
    {
        _pin = pin;
        _device = device;
        _onCancel = onCancel;
        Text = "Pointr — " + S.PairTitle;
        Icon = TrayApp.AppIcon;
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        TopMost = true;
        BackColor = Brand.Bg;
        DoubleBuffered = true;
        AutoScaleMode = AutoScaleMode.None; // laid out by hand from DeviceDpi
        KeyPreview = true;
        KeyDown += (_, e) => { if (e.KeyCode == Keys.Escape) Cancel(); };
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Brand.DarkTitleBar(Handle);
    }

    protected override void OnLoad(EventArgs e)
    {
        base.OnLoad(e);
        Relayout();
        CenterToScreen();
    }

    protected override void OnDpiChanged(DpiChangedEventArgs e)
    {
        base.OnDpiChanged(e);
        Relayout();
    }

    private float UiScale => DeviceDpi / 96f;

    private void Relayout()
    {
        ClientSize = new Size((int)(W * UiScale), (int)(H * UiScale));
        float s = UiScale;
        _cancel = new RectangleF((ClientSize.Width - 136 * s) / 2, 282 * s, 136 * s, 40 * s);
        Invalidate();
    }

    private void Cancel()
    {
        _onCancel();
        Close();
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        bool hover = _cancel.Contains(e.Location);
        if (hover == _cancelHover) return;
        _cancelHover = hover;
        Cursor = hover ? Cursors.Hand : Cursors.Default;
        Invalidate();
    }

    protected override void OnMouseClick(MouseEventArgs e)
    {
        if (_cancel.Contains(e.Location)) Cancel();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        float s = UiScale;
        float w = ClientSize.Width;
        var center = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };

        Brand.DotField(g, ClientRectangle, new PointF(w / 2, 180 * s), s, Brand.Accent);

        using (var brand = new Font(Brand.Display, 9.5f, FontStyle.Regular))
        using (var muted = new SolidBrush(Brand.Muted))
            g.DrawString("P O I N T R", brand, muted, new RectangleF(0, 18 * s, w, 22 * s), center);
        using (var title = new Font(Brand.Ui, 17f, FontStyle.Bold))
        using (var text = new SolidBrush(Brand.Text))
            g.DrawString(S.PairTitle, title, text, new RectangleF(0, 44 * s, w, 36 * s), center);
        using (var hint = new Font(Brand.Ui, 10.5f))
        using (var muted = new SolidBrush(Brand.Muted))
            g.DrawString(S.PairHint(_device), hint, muted, new RectangleF(16 * s, 82 * s, w - 32 * s, 26 * s), center);

        float cw = 74 * s, ch = 96 * s, gap = 14 * s;
        float x0 = (w - (cw * _pin.Length + gap * (_pin.Length - 1))) / 2, y0 = 134 * s;
        using var digitFont = new Font(Brand.Display, 28f);
        using var cell = new SolidBrush(Brand.Panel);
        using var edge = new Pen(Color.FromArgb(110, Brand.Accent), 1.5f * s);
        using var digit = new SolidBrush(Brand.Text);
        for (int i = 0; i < _pin.Length; i++)
        {
            var r = new RectangleF(x0 + i * (cw + gap), y0, cw, ch);
            using var path = Brand.Rounded(r, 20 * s);
            using (var lift = new LinearGradientBrush(r, Color.FromArgb(255, 26, 32, 43), Brand.Panel, 90f))
                g.FillPath(lift, path);
            g.DrawPath(edge, path);
            g.DrawString(_pin[i].ToString(), digitFont, digit, r, center);
        }

        using var pill = Brand.Rounded(_cancel, _cancel.Height / 2);
        using (var fill = new SolidBrush(_cancelHover ? Color.FromArgb(32, 39, 51) : Brand.Cell)) g.FillPath(fill, pill);
        using (var border = new Pen(Brand.Edge, 1f * s)) g.DrawPath(border, pill);
        using var btnFont = new Font(Brand.Ui, 10.5f, FontStyle.Bold);
        using var btnText = new SolidBrush(_cancelHover ? Brand.Text : Brand.Muted);
        g.DrawString(S.Cancel, btnFont, btnText, _cancel, center);
    }
}
