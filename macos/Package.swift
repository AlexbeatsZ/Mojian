// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "MojianMac",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "Mojian", targets: ["MojianMac"])],
    dependencies: [.package(path: "../ios")],
    targets: [
        .target(name: "MojianDesktop", dependencies: [.product(name: "MojianCore", package: "ios")], resources: [.copy("Resources/page-backgrounds.json")]),
        .executableTarget(name: "MojianMac", dependencies: ["MojianDesktop"]),
        .testTarget(name: "MojianDesktopTests", dependencies: ["MojianDesktop"])
    ]
)
