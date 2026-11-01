using System.Text.Json;

namespace Pointr;

internal sealed class PairedDevice
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Key { get; set; } = ""; // base64, 32 bytes
}

internal sealed class Config
{
    public string ServerId { get; set; } = Guid.NewGuid().ToString("N");
    public List<PairedDevice> Devices { get; set; } = [];
    public bool AllowScreen { get; set; } = true;

    private static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Pointr");
    private static readonly string FilePath = Path.Combine(Dir, "config.json");
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    public static Config Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<Config>(File.ReadAllText(FilePath)) ?? new Config();
        }
        catch { /* corrupt file: start fresh */ }
        var c = new Config();
        c.Save();
        return c;
    }

    public void Save()
    {
        lock (this)
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, Json));
        }
    }
}
