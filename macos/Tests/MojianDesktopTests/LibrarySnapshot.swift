import Foundation

/// Every synchronized file's bytes below a library root (device-local `.mojian*` state excluded).
func librarySnapshot(_ root: URL) -> [String: Data] {
    var result = [String: Data]()
    let enumerator = FileManager.default.enumerator(atPath: root.path)
    while let path = enumerator?.nextObject() as? String {
        if path.split(separator: "/").contains(where: { $0.hasPrefix(".mojian") && $0 != ".mojian-folder.json" }) { continue }
        let url = root.appendingPathComponent(path)
        var directory: ObjCBool = false
        if FileManager.default.fileExists(atPath: url.path, isDirectory: &directory), !directory.boolValue { result[path] = try? Data(contentsOf: url) }
    }
    return result
}
