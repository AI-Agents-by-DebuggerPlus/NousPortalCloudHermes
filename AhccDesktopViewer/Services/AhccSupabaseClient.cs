using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace AhccDesktopViewer.Services;

public sealed class AhccMessageRow
{
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    [JsonPropertyName("sender_id")]
    public string? SenderId { get; set; }

    [JsonPropertyName("sender_name")]
    public string SenderName { get; set; } = "";

    [JsonPropertyName("content")]
    public string Content { get; set; } = "";

    [JsonPropertyName("created_at")]
    public string? CreatedAt { get; set; }

    [JsonPropertyName("recipient_name")]
    public string? RecipientName { get; set; }

    [JsonPropertyName("session_id")]
    public string SessionId { get; set; } = "";
}

public sealed class AhccFileRow
{
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    [JsonPropertyName("session_id")]
    public string SessionId { get; set; } = "";

    [JsonPropertyName("sender_name")]
    public string SenderName { get; set; } = "";

    [JsonPropertyName("file_name")]
    public string FileName { get; set; } = "";

    [JsonPropertyName("mime")]
    public string Mime { get; set; } = "text/markdown";

    [JsonPropertyName("content")]
    public string Content { get; set; } = "";

    [JsonPropertyName("byte_size")]
    public int ByteSize { get; set; }

    [JsonPropertyName("created_at")]
    public string? CreatedAt { get; set; }
}

public sealed class AhccFileRef
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("mime")]
    public string Mime { get; set; } = "text/markdown";
}

/// <summary>
/// Supabase PostgREST for ahcc_messages + ahcc_files (AHCCDV is Supabase-only).
/// </summary>
public sealed class AhccSupabaseClient : IDisposable
{
    public const string NewSessionContent = "new session";
    public const string FileMarkerPrefix = "[AHCC_FILE]";
    public const string SenderHermes = "Hermes";
    public const int MaxTextFileBytes = 512 * 1024;

    private static readonly Regex FileMarkerRegex = new(
        @"^\[AHCC_FILE\]\s*(\{.*\})\s*$",
        RegexOptions.Singleline | RegexOptions.Compiled);

    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(60) };
    private readonly string _root;
    private readonly string _anonKey;

    public AhccSupabaseClient(string baseUrl, string anonKey)
    {
        _root = (baseUrl ?? "").Trim().TrimEnd('/');
        _anonKey = (anonKey ?? "").Trim();
    }

    public bool IsConfigured =>
        !string.IsNullOrWhiteSpace(_root) && !string.IsNullOrWhiteSpace(_anonKey);

    public async Task<string?> FetchLastSessionIdAsync(CancellationToken ct = default)
    {
        if (!IsConfigured) return null;
        var url =
            $"{_root}/rest/v1/ahcc_messages?select=session_id,created_at,content&order=created_at.desc&limit=1";
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        AddAuth(req);
        using var res = await _http.SendAsync(req, ct).ConfigureAwait(false);
        var body = await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        if (!res.IsSuccessStatusCode)
            throw new InvalidOperationException($"Supabase last session HTTP {(int)res.StatusCode}: {Truncate(body)}");

        var rows = JsonSerializer.Deserialize<List<AhccMessageRow>>(body) ?? new();
        var sid = rows.Count > 0 ? rows[0].SessionId : null;
        return string.IsNullOrWhiteSpace(sid) ? null : sid;
    }

    public async Task<List<AhccMessageRow>> FetchSessionMessagesAsync(
        string sessionId,
        CancellationToken ct = default)
    {
        if (!IsConfigured || string.IsNullOrWhiteSpace(sessionId))
            return new List<AhccMessageRow>();

        var url =
            $"{_root}/rest/v1/ahcc_messages?select=*&session_id=eq.{Uri.EscapeDataString(sessionId)}&order=created_at.asc";
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        AddAuth(req);
        using var res = await _http.SendAsync(req, ct).ConfigureAwait(false);
        var body = await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        if (!res.IsSuccessStatusCode)
            throw new InvalidOperationException($"Supabase history HTTP {(int)res.StatusCode}: {Truncate(body)}");

        return JsonSerializer.Deserialize<List<AhccMessageRow>>(body) ?? new();
    }

    public async Task<AhccFileRow?> FetchFileAsync(string fileId, CancellationToken ct = default)
    {
        if (!IsConfigured || string.IsNullOrWhiteSpace(fileId)) return null;
        var url = $"{_root}/rest/v1/ahcc_files?select=*&id=eq.{Uri.EscapeDataString(fileId)}&limit=1";
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        AddAuth(req);
        using var res = await _http.SendAsync(req, ct).ConfigureAwait(false);
        var body = await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        if (!res.IsSuccessStatusCode)
            throw new InvalidOperationException($"Supabase file HTTP {(int)res.StatusCode}: {Truncate(body)}");
        var rows = JsonSerializer.Deserialize<List<AhccFileRow>>(body) ?? new();
        return rows.Count > 0 ? rows[0] : null;
    }

    public static bool TryParseFileMarker(string content, out AhccFileRef? fileRef)
    {
        fileRef = null;
        if (string.IsNullOrWhiteSpace(content)) return false;
        var m = FileMarkerRegex.Match(content.Trim());
        if (!m.Success) return false;
        try
        {
            fileRef = JsonSerializer.Deserialize<AhccFileRef>(m.Groups[1].Value);
            return fileRef != null && !string.IsNullOrWhiteSpace(fileRef.Id);
        }
        catch
        {
            return false;
        }
    }

    public static string FormatFileMarker(string id, string name, string mime) =>
        FileMarkerPrefix + JsonSerializer.Serialize(new AhccFileRef
        {
            Id = id,
            Name = name,
            Mime = mime,
        });

    private void AddAuth(HttpRequestMessage req)
    {
        req.Headers.TryAddWithoutValidation("apikey", _anonKey);
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _anonKey);
    }

    private static string Truncate(string? s) =>
        string.IsNullOrEmpty(s) ? "" : (s.Length <= 240 ? s : s[..240] + "…");

    public void Dispose() => _http.Dispose();
}
