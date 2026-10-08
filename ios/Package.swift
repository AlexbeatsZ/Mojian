// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "MojianCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [.library(name: "MojianCore", targets: ["MojianCore"])],
    dependencies: [.package(url: "https://github.com/weichsel/ZIPFoundation.git", exact: "0.9.19")],
    targets: [
        .target(name: "MojianCore", dependencies: ["ZIPFoundation"]),
        .testTarget(name: "MojianCoreTests", dependencies: ["MojianCore"], resources: [.copy("Fixtures")])
    ]
)
