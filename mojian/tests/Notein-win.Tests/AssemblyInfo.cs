using Xunit;

// WPF permits a single Application per test process; real-canvas tests must
// not race Application.Current ?? new Application() across test classes.
[assembly: CollectionBehavior(DisableTestParallelization = true)]
