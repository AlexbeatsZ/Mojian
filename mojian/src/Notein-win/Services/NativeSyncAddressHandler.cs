using System.Net.Http;
using System.Net.Security;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Notein_win.Services;

// The engine retries its durable operation at the next address; request bodies are never replayed here.
internal sealed class NativeSyncAddressHandler : HttpMessageHandler
{
    private readonly Uri _binding;
    private readonly Uri[] _addresses;
    private readonly HttpMessageInvoker[] _routes;
    private int _current;
    internal int Count => _routes.Length;
    internal string CurrentAddress => _addresses[_current].AbsoluteUri;
    internal void Select(int index) => _current = index;

    internal NativeSyncAddressHandler(NativeSyncConfiguration configuration)
    {
        _binding = new Uri(configuration.Endpoint.TrimEnd('/') + "/");
        _addresses = configuration.ServerAddresses.Select(value => new Uri(value)).ToArray();
        _routes = _addresses.Select(address =>
        {
            configuration.CertificatePins.TryGetValue(address.AbsoluteUri, out var pin);
            if (pin != null && !NativeSyncStorage.ValidHash(pin)) throw new ArgumentException("服务器证书指纹无效");
            var handler = new SocketsHttpHandler { AllowAutoRedirect = false, UseProxy = false, ConnectTimeout = TimeSpan.FromSeconds(5) };
            if (pin != null) handler.SslOptions.RemoteCertificateValidationCallback = (_, certificate, _, errors) => ValidatePinnedCertificate(certificate, errors, pin);
            return new HttpMessageInvoker(handler);
        }).ToArray();
    }

    internal static bool ValidatePinnedCertificate(X509Certificate? certificate, SslPolicyErrors errors, string pin)
    {
        if (certificate == null || (errors & ~SslPolicyErrors.RemoteCertificateChainErrors) != SslPolicyErrors.None) return false;
        using var leaf = new X509Certificate2(certificate);
        return DateTime.UtcNow >= leaf.NotBefore.ToUniversalTime() && DateTime.UtcNow <= leaf.NotAfter.ToUniversalTime() &&
            CryptographicOperations.FixedTimeEquals(SHA256.HashData(leaf.RawData), Convert.FromHexString(pin));
    }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellation)
    {
        var relative = _binding.MakeRelativeUri(request.RequestUri!);
        if (relative.IsAbsoluteUri || relative.OriginalString.StartsWith("..", StringComparison.Ordinal)) throw new InvalidOperationException("同步请求越过配置路径");
        request.RequestUri = new Uri(_addresses[_current], relative);
        using var probe = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        if (relative.OriginalString == "manifest") probe.CancelAfter(TimeSpan.FromSeconds(8));
        return await _routes[_current].SendAsync(request, probe.Token).ConfigureAwait(false);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) foreach (var route in _routes) route.Dispose();
        base.Dispose(disposing);
    }
}
