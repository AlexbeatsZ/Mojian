import AppKit

@MainActor final class ReadOnlySyncController {
    private weak var window: MacWindow?
    private let engine: ReadOnlySync
    private var task: Task<Void, Never>?
    private var timer: Timer?
    private var closed = false
    init(window: MacWindow, engine: ReadOnlySync) {
        self.window = window; self.engine = engine
        timer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.request() }
        }
    }
    func request() {
        guard !closed, task == nil, NSApp.isActive, let window, window.window?.isVisible == true else { return }
        task = Task { [weak self, engine] in
            defer { self?.task = nil }
            do {
                let result = try await engine.synchronize(apply: { [weak self] action in
                    guard let receiver = self else { throw CancellationError() }
                    try await receiver.apply(action)
                }, progress: { [weak self] text in
                    await self?.status(text)
                })
                self?.status("笔记已同步 · 只读")
                self?.window?.session.syncRevision = result.revision
            } catch {
                guard !Task.isCancelled else { self?.status("只读同步已暂停 · 返回前台后继续"); return }
                if await engine.hasPendingIncoming {
                    self?.window?.session.suspendReadOnlyLibrary()
                    self?.window?.canvas.rebind(); self?.window?.refresh(reloadTree: true)
                }
                self?.status("同步未完成：\(error.localizedDescription)")
            }
        }
    }
    private func status(_ text: String) { window?.session.syncStatus = text; window?.refresh() }
    private func apply(_ action: @Sendable () throws -> Void) throws {
        guard let window, !closed else { throw CancellationError() }
        try window.applyReadOnlySync(action)
    }
    func pause() { task?.cancel() }
    func close() { closed = true; timer?.invalidate(); timer = nil; task?.cancel() }
}
