using Xunit;

// UI tests share one Application/Dispatcher and process-wide clipboard state.
[assembly: CollectionBehavior(DisableTestParallelization = true)]
[assembly: TestFramework("Notein_win.Tests.WpfTestFramework", "Notein-win.Tests")]
