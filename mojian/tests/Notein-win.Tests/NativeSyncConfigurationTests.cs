using Notein_win.Services;
using Xunit;

namespace Notein_win.Tests;

public class NativeSyncConfigurationTests
{
    [Fact]
    public void Default_UsesSakuraHttpsWithoutEnablingSyncOrShippingCredentials()
    {
        var configuration = new NativeSyncConfiguration();
        Assert.Equal("https://cdn.u3146344.nyat.app:63573/mojian-sync/v1/", configuration.Endpoint);
        Assert.False(configuration.Enabled);
        Assert.Empty(configuration.Token);
    }
}
