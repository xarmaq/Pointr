using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using Microsoft.Win32;

namespace Pointr;

internal sealed class TrayApp : ApplicationContext
{
    public static readonly Icon AppIcon = LoadIcon();
    /// <summary>The app icon with a red "live" dot, shown while the screen is being viewed.</summary>
    private static readonly Icon LiveIcon = MakeLiveIcon();

    private static Icon MakeLiveIcon()
    {
        int size = SystemInformation.SmallIconSize.Width * 2;
        using var bmp = new Icon(AppIcon, size, size).ToBitmap();
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            float d = size * 0.46f;
            g.FillEllipse(Brushes.Black, size - d - 1, size - d - 1, d + 2, d + 2);
            using var red = new SolidBrush(Color.FromArgb(255, 59, 74));
            g.FillEllipse(red, size - d, size - d, d, d);
        }
        return Icon.FromHandle(bmp.GetHicon());
    }

    private static readonly Font MenuFont = new(Brand.Ui, 10.5f);
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private readonly Config _config = Config.Load();
    private readonly NotifyIcon _tray;
    private readonly Control _ui = new();
    private readonly Server _server;
    private readonly BlurOverlay _blur = new();

    public TrayApp()
    {
        _ = _ui.Handle; // bind the marshalling control to this (UI) thread
        var input = new InputService();
        var laser = new LaserOverlay(_ui);
        _server = new Server(_config, input, new AudioService(), new WindowService(input), _blur, laser, _ui);

        ToolStripManager.Renderer = new DarkMenuRenderer(); // submenus pick this up too
        var menu = new ContextMenuStrip { Font = MenuFont, BackColor = Brand.Panel, ForeColor = Brand.Text, Padding = new Padding(4) };
        _tray = new NotifyIcon { Icon = AppIcon, Text = "Pointr", Visible = true, ContextMenuStrip = menu };
        _tray.ContextMenuStrip.Opening += (_, _) => BuildMenu();
        _tray.MouseClick += (_, e) =>
        {
            if (e.Button == MouseButtons.Left)
                typeof(NotifyIcon).GetMethod("ShowContextMenu", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance)
                    ?.Invoke(_tray, null);
        };
        _server.SessionsChanged += () => _ui.BeginInvoke(UpdateTooltip);
        _server.Start();
        _server.Screen.StreamingChanged += () => _ui.BeginInvoke(UpdateTooltip);
        UpdateTooltip();
        BuildMenu();
    }

    private void UpdateTooltip()
    {
        var names = _server.ConnectedNames.ToList();
        var text = "Pointr — " + (names.Count > 0 ? S.Connected(string.Join(", ", names)) : S.Ready);
        if (_server.Screen.Streaming) text += "\n" + S.ScreenLive;
        _tray.Icon = _server.Screen.Streaming ? LiveIcon : AppIcon;
        _tray.Text = text.Length > 127 ? text[..127] : text;
    }

    private void BuildMenu()
    {
        var m = _tray.ContextMenuStrip!;
        m.Items.Clear();
        var names = _server.ConnectedNames.ToList();
        m.Items.Add(new ToolStripMenuItem($"POINTR  ·  {Environment.MachineName}") { Enabled = false, Font = new Font(Brand.Display, 9f) });
        m.Items.Add(new ToolStripMenuItem(names.Count > 0 ? S.Connected(string.Join(", ", names)) : S.Ready) { Enabled = false });
        m.Items.Add(new ToolStripMenuItem(S.Address(string.Join(", ", LocalAddresses()))) { Enabled = false });
        m.Items.Add(new ToolStripSeparator());

        var devices = new ToolStripMenuItem(S.PairedDevices);
        devices.DropDown.Font = MenuFont;
        List<PairedDevice> paired;
        lock (_config) paired = _config.Devices.ToList();
        if (paired.Count == 0) devices.DropDownItems.Add(new ToolStripMenuItem(S.None) { Enabled = false });
        foreach (var d in paired)
        {
            var item = new ToolStripMenuItem(d.Name);
            item.DropDown.Font = MenuFont;
            item.DropDownItems.Add(S.Forget, null, (_, _) => _server.Forget(d.Id));
            devices.DropDownItems.Add(item);
        }
        m.Items.Add(devices);

        m.Items.Add(new ToolStripMenuItem(S.AllowScreen, null, (_, _) => _server.AllowScreen = !_server.AllowScreen) { Checked = _server.AllowScreen });
        m.Items.Add(new ToolStripMenuItem(S.Autostart, null, (_, _) => ToggleAutostart()) { Checked = AutostartEnabled() });
        m.Items.Add(S.Firewall, null, (_, _) => AddFirewallRule());
        m.Items.Add(new ToolStripSeparator());
        m.Items.Add(S.Exit, null, (_, _) => { _tray.Visible = false; _blur.Hide(); ExitThread(); });
    }

    private static IEnumerable<string> LocalAddresses() =>
        NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a.Address)
                        && !a.Address.ToString().StartsWith("169.254."))
            .Select(a => a.Address.ToString())
            .Distinct();

    private static bool AutostartEnabled()
    {
        using var k = Registry.CurrentUser.OpenSubKey(RunKey);
        return k?.GetValue("Pointr") is string;
    }

    private static void ToggleAutostart()
    {
        using var k = Registry.CurrentUser.CreateSubKey(RunKey);
        if (AutostartEnabled()) k.DeleteValue("Pointr", false);
        else k.SetValue("Pointr", $"\"{Environment.ProcessPath}\"");
    }

    private static void AddFirewallRule()
    {
        var exe = Environment.ProcessPath;
        var args = "/c netsh advfirewall firewall delete rule name=\"Pointr\" & " +
                   $"netsh advfirewall firewall add rule name=\"Pointr\" dir=in action=allow program=\"{exe}\" enable=yes profile=private,domain";
        try
        {
            Process.Start(new ProcessStartInfo("cmd.exe", args)
            {
                Verb = "runas", UseShellExecute = true, WindowStyle = ProcessWindowStyle.Hidden,
            });
        }
        catch { /* user declined UAC */ }
    }

    private static Icon LoadIcon()
    {
        using var s = typeof(TrayApp).Assembly.GetManifestResourceStream("Pointr.app.ico");
        return s != null ? new Icon(s) : SystemIcons.Application;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) { _tray.Dispose(); _ui.Dispose(); }
        base.Dispose(disposing);
    }
}
