import UIKit
import MojianCore

@main final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config=UISceneConfiguration(name:"Workspace",sessionRole:session.role); config.delegateClass=SceneDelegate.self; return config
    }
}
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let scene=scene as? UIWindowScene else { return }
        let window=UIWindow(windowScene:scene); window.overrideUserInterfaceStyle = .dark
        do {
            let root=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("Workspace")
            let model=try WorkspaceSession(root:root), controller=WorkspaceController(session:model)
            window.rootViewController=controller
        } catch {
            let vc=UIViewController(); vc.view.backgroundColor = .black
            let label=UILabel(); label.text="无法打开笔记。\n\(error.localizedDescription)\n\n原始文件已保留，请先备份后检查。"; label.numberOfLines=0; label.textColor = .white
            label.frame=CGRect(x:24,y:100,width:340,height:220); vc.view.addSubview(label); window.rootViewController=vc
        }
        self.window=window; window.makeKeyAndVisible()
        if let url=options.urlContexts.first?.url { (window.rootViewController as? WorkspaceController)?.importURL(url) }
    }
    func sceneDidEnterBackground(_ scene: UIScene) { (window?.rootViewController as? WorkspaceController)?.session.flush() }
    func sceneWillResignActive(_ scene: UIScene) { (window?.rootViewController as? WorkspaceController)?.cancelInput() }
    func scene(_ scene: UIScene, openURLContexts contexts: Set<UIOpenURLContext>) {
        if let url=contexts.first?.url { (window?.rootViewController as? WorkspaceController)?.importURL(url) }
    }
}
