using Xunit.Abstractions;
using Xunit.Sdk;

namespace Notein_win.Tests;

public sealed class WpfTestFramework(IMessageSink messageSink) : XunitTestFramework(messageSink), IDisposable
{
    public new void Dispose()
    {
        try
        {
            WpfTestExecutor.Shutdown();
        }
        finally
        {
            base.Dispose();
        }
    }
}
