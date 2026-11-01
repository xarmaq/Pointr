using System.Globalization;

namespace Pointr;

internal static class S
{
    private static readonly bool Ru = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "ru";

    public static string Ready => Ru ? "Готов к подключению" : "Ready to connect";
    public static string Connected(string names) => Ru ? $"Подключено: {names}" : $"Connected: {names}";
    public static string Address(string ip) => Ru ? $"Адрес: {ip}" : $"Address: {ip}";
    public static string PairedDevices => Ru ? "Сопряжённые устройства" : "Paired devices";
    public static string None => Ru ? "Нет" : "None";
    public static string Forget => Ru ? "Забыть" : "Forget";
    public static string Autostart => Ru ? "Запускать вместе с Windows" : "Start with Windows";
    public static string Firewall => Ru ? "Разрешить в брандмауэре…" : "Allow through firewall…";
    public static string AllowScreen => Ru ? "Разрешить трансляцию экрана" : "Allow screen view";
    public static string ScreenLive => Ru ? "Экран транслируется на телефон" : "Screen is shown on the phone";
    public static string Exit => Ru ? "Выход" : "Quit";
    public static string PairTitle => Ru ? "Подключение телефона" : "Pair a phone";
    public static string PairHint(string device) => Ru
        ? $"Введите этот код на «{device}»"
        : $"Enter this code on “{device}”";
    public static string Cancel => Ru ? "Отмена" : "Cancel";
    public static string AlreadyRunning => Ru ? "Pointr уже запущен — ищите значок в трее." : "Pointr is already running — look for it in the tray.";
}
