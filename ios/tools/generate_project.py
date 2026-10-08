"""Generate the checked-in Xcode project without a global XcodeGen installation."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parents[1]
def ident(s):
    return hashlib.sha1(s.encode()).hexdigest()[:24].upper()
def q(s):
    return json.dumps(str(s))
objects = {}
def add(key, value):
    objects[ident(key)] = value
    return ident(key)
files, builds = [], []
for path in sorted((root / "App").glob("*.swift")):
    rel = path.relative_to(root)
    f = add(f"file:{rel}", f"isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {q(rel)}; sourceTree = SOURCE_ROOT;")
    files.append(f)
    builds.append(add(f"build:{rel}", f"isa = PBXBuildFile; fileRef = {f};"))
product = add("product", 'isa = PBXFileReference; explicitFileType = wrapper.application; path = Mojian.app; sourceTree = BUILT_PRODUCTS_DIR;')
core = add("core", 'isa = XCSwiftPackageProductDependency; productName = MojianCore;')
framework = add("core-build", f"isa = PBXBuildFile; productRef = {core};")
local = add("local", 'isa = XCLocalSwiftPackageReference; relativePath = ".";')
source = add("sources", f"isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = ({','.join(builds)}); runOnlyForDeploymentPostprocessing = 0;")
frameworks = add("frameworks", f"isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = ({framework}); runOnlyForDeploymentPostprocessing = 0;")
asset = add("assets", 'isa = PBXFileReference; lastKnownFileType = folder.assetcatalog; path = App/Assets.xcassets; sourceTree = SOURCE_ROOT;')
asset_build = add("assets-build", f"isa = PBXBuildFile; fileRef = {asset};")
notice = add("notice", 'isa = PBXFileReference; lastKnownFileType = text; path = THIRD_PARTY_NOTICES.md; sourceTree = SOURCE_ROOT;')
notice_build = add("notice-build", f"isa = PBXBuildFile; fileRef = {notice};")
resources = add("resources", f"isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = ({asset_build},{notice_build}); runOnlyForDeploymentPostprocessing = 0;")
group = add("group", f"isa = PBXGroup; children = ({','.join(files+[asset,notice,product])}); sourceTree = '<group>';")
configs=[]
for name in ("Debug","Release"):
    settings = {
        "PRODUCT_BUNDLE_IDENTIFIER":"com.meta.mojian", "PRODUCT_NAME":"Mojian", "SWIFT_VERSION":"5.0",
        "IPHONEOS_DEPLOYMENT_TARGET":"17.0", "SDKROOT":"iphoneos", "TARGETED_DEVICE_FAMILY":"1,2",
        "SUPPORTED_PLATFORMS":"iphoneos iphonesimulator", "SUPPORTS_MACCATALYST":"NO", "SUPPORTS_XR_DESIGNED_FOR_IPHONE_IPAD":"NO",
        "INFOPLIST_FILE":"App/Info.plist", "GENERATE_INFOPLIST_FILE":"NO", "CODE_SIGN_STYLE":"Automatic",
        "ASSETCATALOG_COMPILER_APPICON_NAME":"AppIcon", "MARKETING_VERSION":"0.1.0", "CURRENT_PROJECT_VERSION":"1",
        "SWIFT_OPTIMIZATION_LEVEL":"-Onone" if name=="Debug" else "-O", "DEBUG_INFORMATION_FORMAT":"dwarf" if name=="Debug" else "dwarf-with-dsym",
        "SWIFT_ACTIVE_COMPILATION_CONDITIONS":"DEBUG" if name=="Debug" else "", "CLANG_ENABLE_MODULES":"YES"
    }
    configs.append(add("config:"+name, "isa = XCBuildConfiguration; name = "+name+"; buildSettings = {"+"".join(f"{k} = {q(v)};" for k,v in settings.items())+"};"))
configlist = add("configlist", f"isa = XCConfigurationList; buildConfigurations = ({','.join(configs)}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;")
target=add("target",f"isa = PBXNativeTarget; name = Mojian; productName = Mojian; productReference = {product}; productType = 'com.apple.product-type.application'; buildConfigurationList = {configlist}; buildPhases = ({source},{frameworks},{resources}); buildRules = (); dependencies = (); packageProductDependencies = ({core});")
project=add("project",f"isa = PBXProject; attributes = {{LastUpgradeCheck = 2700;}}; buildConfigurationList = {configlist}; compatibilityVersion = 'Xcode 14.0'; developmentRegion = zh-Hans; knownRegions = (en,zh-Hans,Base); mainGroup = {group}; productRefGroup = {group}; projectDirPath = ''; projectRoot = ''; targets = ({target}); packageReferences = ({local});")
project_dir=root/"Mojian.xcodeproj"
project_dir.mkdir(exist_ok=True)
# OpenStep strings require double quotes, never single quotes.
content="// !$*UTF8*$!\n{archiveVersion = 1; classes = {}; objectVersion = 56; objects = {\n"+"\n".join(f"{key} = {{{value}}};" for key,value in objects.items())+f"\n}}; rootObject = {project};}}\n"
content=content.replace("'<group>'",'"<group>"').replace("'com.apple.product-type.application'",'"com.apple.product-type.application"').replace("'Xcode 14.0'",'"Xcode 14.0"').replace("''",'""')
(project_dir/"project.pbxproj").write_text(content)
scheme_dir=project_dir/"xcshareddata/xcschemes"
scheme_dir.mkdir(parents=True,exist_ok=True)
ref=f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="Mojian.app" BlueprintName="Mojian" ReferencedContainer="container:Mojian.xcodeproj"/>'
(scheme_dir/"Mojian.xcscheme").write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2700" version="1.3"><BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{ref}</BuildActionEntry></BuildActionEntries></BuildAction><TestAction buildConfiguration="Debug"/><LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{ref}</BuildableProductRunnable></LaunchAction><ProfileAction buildConfiguration="Release"><BuildableProductRunnable runnableDebuggingMode="0">{ref}</BuildableProductRunnable></ProfileAction><AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/></Scheme>''')
