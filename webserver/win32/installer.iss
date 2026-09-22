; Inno Setup script for the ADBlock Windows web server.
; Build: iscc.exe installer.iss   (output: dist-installer\adblock-webserver-setup.exe)
[Setup]
; Stable identity so an upgrade replaces the previous install (and keeps its
; folder as the default) instead of installing side by side.
AppId={{8F2A61D4-6C0B-4B3E-9E77-ADB10C1A5F27}
AppName=ADBlock 拦截服务器
AppVersion=1.36
AppPublisher=wxcvm
DefaultDirName={autopf}\ADBlock
DefaultGroupName=ADBlock
DisableProgramGroupPage=yes
; Never skip the destination page: on an upgrade Inno hides it, so the folder
; could not be changed (only /VERYSILENT skips it now).
DisableDirPage=no
UsePreviousAppDir=yes
OutputDir=dist-installer
OutputBaseFilename=adblock-webserver-setup
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest
WizardStyle=modern
; A running webserver.exe would lock its files: let Inno close it (and the
; code below kills it explicitly as well, which also covers the tray process).
CloseApplications=force
RestartApplications=no

[Languages]
Name: "chinese"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务:"; Flags: unchecked
Name: "autostart"; Description: "开机自动启动"; GroupDescription: "附加任务:"; Flags: unchecked

[Files]
; Program files are always replaced, but the resources folder is USER DATA:
; it holds the CA (.crt/.key), statistics (*.dat), the exported rule files
; (blocklist.txt / cosmetic.css), allowlist.txt, webserver.ini side files and
; any custom block image. Never overwrite those on an upgrade
; (onlyifdoesntexist), so updating keeps the certificate and the statistics.
Source: "dist\*"; DestDir: "{app}"; Excludes: "resources\*"; Flags: recursesubdirs createallsubdirs ignoreversion
Source: "dist\resources\*"; DestDir: "{app}\resources"; Excludes: "localhost-2410.*,*.dat,update_cache.json,webserver.log,webserver.log.1,crash.log,allowlist.txt,blocklist.txt,cosmetic.css"; Flags: recursesubdirs createallsubdirs onlyifdoesntexist

[Icons]
Name: "{group}\ADBlock 仪表盘"; Filename: "{app}\webserver.exe"
Name: "{group}\卸载 ADBlock"; Filename: "{uninstallexe}"
Name: "{autodesktop}\ADBlock"; Filename: "{app}\webserver.exe"; Tasks: desktopicon


[Run]
; Register autostart through the application's own HKCU Run key. A Startup
; folder shortcut (as before) plus that Run key meant TWO autostart entries and
; therefore two server processes after logon.
Filename: "{app}\webserver.exe"; Parameters: "--install-autostart"; Tasks: autostart; Flags: runhidden skipifsilent
Filename: "{app}\webserver.exe"; Description: "立即启动 ADBlock"; Flags: nowait skipifsilent
; The in-app updater installs with /VERYSILENT. Every entry above is skipped in
; that mode (skipifsilent) and RestartApplications=no keeps Inno from bringing
; the server back either, so the update killed the running dashboard and left
; nothing behind - it looked like "更新失败". Start it again for the silent path.
Filename: "{app}\webserver.exe"; Flags: nowait; Check: WizardSilent

[UninstallRun]
Filename: "{app}\webserver.exe"; Parameters: "--uninstall-autostart"; Flags: runhidden skipifdoesntexist

[Code]
function InitializeSetup(): Boolean;
var
  ResultCode: Integer;
  Pid: String;
begin
  { Only stop the instance the updater pointed at (/PID=...), so a server
    running from a different folder or user session is never killed. Manual
    installs (no /PID) fall back to the historic name-based kill. }
  Pid := ExpandConstant('{param:PID|}');
  if Pid <> '' then
    Exec('taskkill.exe', '/F /PID ' + Pid, '', SW_HIDE, ewWaitUntilTerminated, ResultCode)
  else
    Exec('taskkill.exe', '/F /IM webserver.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Result := True;
end;
