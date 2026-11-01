namespace Pointr;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        using var mutex = new Mutex(true, @"Local\Pointr.Server", out bool first);
        if (!first)
        {
            MessageBox.Show(S.AlreadyRunning, "Pointr", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        ApplicationConfiguration.Initialize();
        Application.Run(new TrayApp());
    }
}
