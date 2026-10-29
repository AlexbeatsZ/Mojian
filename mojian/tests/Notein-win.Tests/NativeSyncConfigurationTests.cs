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

    [Fact]
    public void OrderedAddresses_PreserveOrderAndNormalizeBareIpWithoutChangingBinding()
    {
        var addresses = NativeSyncConfiguration.ParseAddresses("192.168.31.32:18743\n\n100.106.169.46:18743\nhttps://example.com/mojian-sync/v1");
        Assert.Equal(new[] { "https://192.168.31.32:18743/mojian-sync/v1/", "https://100.106.169.46:18743/mojian-sync/v1/", "https://example.com/mojian-sync/v1/" }, addresses);
        var config = new NativeSyncConfiguration { Addresses = addresses };
        Assert.Equal(NativeSyncConfiguration.DefaultEndpoint, config.Endpoint);
        Assert.Equal(addresses, config.ServerAddresses);
        Assert.Equal(addresses.Reverse(), new NativeSyncConfiguration { Addresses = addresses.Reverse().ToArray() }.ServerAddresses);
        Assert.Throws<ArgumentException>(() => NativeSyncConfiguration.ParseAddresses(" \n"));
        Assert.Throws<ArgumentException>(() => NativeSyncConfiguration.ParseAddresses("https://user:secret@example.com/"));
    }
}
