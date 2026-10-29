using System.IO;
using Notein_win.Services;

namespace Notein_win.Tests;

public class WorkspaceUiMutationGateTests
{
    [Fact]
    public void EligibilityExcludesReentryAndFailureReopensGate()
    {
        var gate = new WorkspaceUiMutationGate();
        Assert.Throws<IOException>(() => gate.TryRun(() =>
        {
            Assert.False(gate.TryRun(() => true, () => Assert.Fail("reentered")));
            throw new IOException("eligibility failed");
        }, () => Assert.Fail("ineligible operation")));
        Assert.False(gate.TryRun(() => false, () => Assert.Fail("ineligible operation")));
        Assert.True(gate.TryRun(() => true, () => { }));
    }

    [Fact]
    public void DeletionExcludesIncomingAndGateReopensAfterFailure()
    {
        var gate = new WorkspaceUiMutationGate();
        var applied = false;

        Assert.Throws<IOException>(() => gate.Run(() =>
        {
            Assert.False(gate.TryRun(() => true, () => applied = true));
            throw new IOException("commit failed");
        }));

        Assert.False(applied);
        Assert.True(gate.TryRun(() => true, () => applied = true));
        Assert.True(applied);
    }

    [Fact]
    public void UnsavedOrBusyEditorDoesNotApplyIncoming()
    {
        var gate = new WorkspaceUiMutationGate();
        var applied = false;

        Assert.False(gate.TryRun(() => false, () => applied = true));

        Assert.False(applied);
    }

    [Fact]
    public void IncomingExcludesReentrantDeletion()
    {
        var gate = new WorkspaceUiMutationGate();

        Assert.True(gate.TryRun(() => true, () =>
            Assert.Throws<InvalidOperationException>(() => gate.Run(() => true))));
    }
}
