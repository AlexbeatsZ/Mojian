using System.IO;
using System.Text.Json;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.ViewModels;

public sealed partial class MainViewModel
{
    private CancellationTokenSource? _searchCancellation;

    private async Task RunSearchAsync(string query)
    {
        _searchCancellation?.Cancel();
        var cancellation = new CancellationTokenSource();
        _searchCancellation = cancellation;
        SearchResults.Clear();
        var normalized = query?.Trim();
        try
        {
            if (string.IsNullOrWhiteSpace(normalized) || normalized.Length < 2) return;
            var store = _store;
            var pages = _library.Notebooks.SelectMany(notebook => notebook.FlattenAllSections()
                .SelectMany(section => section.Pages.Select(page => new SearchResult
                {
                    NotebookId = notebook.Id, SectionId = section.Id, PageId = page.Id, PageTitle = page.Title,
                }))).ToArray();
            var liveText = new[] { PrimaryDocument, SecondaryDocument }.OfType<PageDocument>()
                .GroupBy(document => (document.NotebookId, document.PageId))
                .ToDictionary(group => group.Key, group => group.First().TextItems.Select(item => item.Text).ToArray());
            await Task.Delay(200, cancellation.Token);
            var found = await Task.Run(() =>
            {
                var results = new List<SearchResult>();
                foreach (var page in pages)
                {
                    cancellation.Token.ThrowIfCancellationRequested();
                    var context = string.Empty;
                    if (!page.PageTitle.Contains(normalized, StringComparison.CurrentCultureIgnoreCase))
                    {
                        var text = liveText.GetValueOrDefault((page.NotebookId, page.PageId));
                        context = text?.FirstOrDefault(value => value.Contains(normalized, StringComparison.CurrentCultureIgnoreCase))
                            ?? (text == null ? SearchStoredText(store, page, normalized) : null) ?? string.Empty;
                        if (string.IsNullOrWhiteSpace(context)) continue;
                        context = context.ReplaceLineEndings(" ").Trim();
                        if (context.Length > 48) context = context[..48] + "…";
                    }
                    results.Add(new SearchResult
                    {
                        NotebookId = page.NotebookId, SectionId = page.SectionId, PageId = page.PageId,
                        PageTitle = page.PageTitle, Context = context,
                    });
                    if (results.Count >= 50) break;
                }
                return results;
            }, cancellation.Token);
            if (cancellation.IsCancellationRequested || !ReferenceEquals(store, _store)) return;
            foreach (var result in found) SearchResults.Add(result);
            OnPropertyChanged(nameof(IsSearchOpen));
        }
        catch (OperationCanceledException) { }
        finally { cancellation.Dispose(); if (ReferenceEquals(_searchCancellation, cancellation)) _searchCancellation = null; }
    }

    private static string? SearchStoredText(WorkspaceStore store, SearchResult page, string query)
    {
        try
        {
            // Search never registers a page as an editable observation or repairs source files.
            using var stream = new FileStream(store.GetPageDocumentPath(page.NotebookId, page.PageId), FileMode.Open,
                FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            using var document = JsonDocument.Parse(stream, new JsonDocumentOptions
            {
                AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip,
            });
            if (!document.RootElement.TryGetProperty("textItems", out var items) || items.ValueKind != JsonValueKind.Array) return null;
            foreach (var item in items.EnumerateArray())
                if (item.TryGetProperty("text", out var text) && text.ValueKind == JsonValueKind.String &&
                    text.GetString() is { } value && value.Contains(query, StringComparison.CurrentCultureIgnoreCase)) return value;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException)
        { System.Diagnostics.Trace.TraceWarning($"Search skipped {page.NotebookId}/{page.PageId}: {error.Message}"); }
        return null;
    }
}
