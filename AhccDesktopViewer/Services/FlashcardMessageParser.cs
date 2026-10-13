using System.Text.Json;

namespace AhccDesktopViewer.Services;

public sealed record FlashcardCard(string En, string Ru, string When);

public static class FlashcardMessageParser
{
    public static IReadOnlyList<FlashcardCard> ExtractCards(string content, string? createdAt)
    {
        var cards = new List<FlashcardCard>();
        foreach (var slice in JsonObjects(content))
        {
            if (TryCard(slice, out var en, out var ru))
                cards.Add(new FlashcardCard(en, ru, createdAt ?? ""));
        }
        return cards;
    }

    public static bool TryStatus(string content, out string status)
    {
        status = "";
        foreach (var slice in JsonObjects(content))
        {
            if (!TrySkill(slice, out var line)) continue;
            status = line;
            return true;
        }
        return false;
    }

    /// <summary>Chat text with flashcard JSON removed. Empty when the message was only a card.</summary>
    public static string WithoutCardJson(string content)
    {
        var text = content ?? "";
        foreach (var slice in JsonObjects(text).Where(s => TryCard(s, out _, out _) || TrySkill(s, out _)))
            text = text.Replace(slice, " ");
        return string.Join(" ", text.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
    }

    private static bool TryCard(string json, out string en, out string ru)
    {
        en = "";
        ru = "";
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            if (!root.TryGetProperty("type", out var typeEl)) return false;
            if (!string.Equals(typeEl.GetString(), "flashcard", StringComparison.OrdinalIgnoreCase)) return false;
            if (!root.TryGetProperty("en", out var enEl) || !root.TryGetProperty("ru", out var ruEl)) return false;
            en = (enEl.GetString() ?? "").Trim();
            ru = (ruEl.GetString() ?? "").Trim();
            if (en.Length == 0 || ru.Length == 0) return false;
            if (IsInstructionSample(en, ru)) return false;
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static bool TrySkill(string json, out string status)
    {
        status = "";
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            if (!root.TryGetProperty("skill", out var skillEl)) return false;
            var skill = skillEl.GetString()?.Trim().ToLowerInvariant();
            if (skill == "flashcard_stop")
            {
                status = "Остановка цикла карточек";
                return true;
            }
            if (skill != "flashcard_start") return false;
            var topic = root.TryGetProperty("topic", out var topicEl) ? (topicEl.GetString() ?? "").Trim() : "";
            if (topic.Contains("тема по-английски", StringComparison.OrdinalIgnoreCase) ||
                topic.Contains('<'))
                return false;
            var interval = ReadInt(root, "interval_minutes", 15);
            var delay = ReadInt(root, "delay_minutes", 0);
            status = string.IsNullOrEmpty(topic)
                ? $"Карточки · каждые {interval} мин · старт через {delay} мин"
                : $"Тема «{topic}» · каждые {interval} мин · старт через {delay} мин";
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static bool IsInstructionSample(string en, string ru) =>
        en.Contains("короткая английская", StringComparison.OrdinalIgnoreCase) ||
        ru.Contains("живой русский", StringComparison.OrdinalIgnoreCase) ||
        en.Contains('<') || ru.Contains('<');

    private static int ReadInt(JsonElement root, string name, int fallback)
    {
        if (!root.TryGetProperty(name, out var el)) return fallback;
        if (el.ValueKind == JsonValueKind.Number && el.TryGetInt32(out var n)) return n;
        if (el.ValueKind == JsonValueKind.String && int.TryParse(el.GetString(), out var s)) return s;
        return fallback;
    }

    private static IEnumerable<string> JsonObjects(string text)
    {
        if (string.IsNullOrEmpty(text)) yield break;
        for (var i = 0; i < text.Length; i++)
        {
            if (text[i] != '{') continue;
            var depth = 0;
            for (var j = i; j < text.Length; j++)
            {
                if (text[j] == '{') depth++;
                else if (text[j] == '}')
                {
                    depth--;
                    if (depth == 0)
                    {
                        yield return text[i..(j + 1)];
                        i = j;
                        break;
                    }
                }
            }
        }
    }
}
