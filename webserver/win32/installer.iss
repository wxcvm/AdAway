; Inno Setup script for the ADBlock Windows web server.
; Build: iscc.exe installer.iss   (output: dist-installer\adblock-webserver-setup.exe)
[Setup]
AppName=ADBlock 拦截服务器
AppVersion=1.14
AppPublisher=wxcvm
DefaultDirName={autopf}\ADBlock
DefaultGroupName=ADBlock
DisableProgramGroupPage=yes
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
Source: "dist\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\ADBlock 仪表盘"; Filename: "{app}\webserver.exe"
Name: "{group}\卸载 ADBlock"; Filename: "{uninstallexe}"
Name: "{autodesktop}\ADBlock"; Filename: "{app}\webserver.exe"; Tasks: desktopicon
Name: "{userstartup}\ADBlock"; Filename: "{app}\webserver.exe"; Parameters: "--minimized"; Tasks: autostart

[Run]
Filename: "{app}\webserver.exe"; Description: "立即启动 ADBlock"; Flags: nowait

[Code]
function InitializeSetup(): Boolean;
var
  ResultCode: Integer;
begin
  { Stop a running instance so its files can be replaced (also covers the
    silent update path used by the in-app updater). }
  Exec('taskkill.exe', '/F /IM webserver.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Result := True;
end;
