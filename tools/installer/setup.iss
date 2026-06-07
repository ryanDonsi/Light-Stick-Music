#define AppName      "Beat Accuracy Checker"
#define AppVersion   "1.0.0"
#define AppPublisher "LightStick Music"
#define AppExeName   "BeatAccuracyChecker.exe"
#define ToolsDir     ".."
#define DistDir      "dist"

[Setup]
AppId={{8F3A2C1D-4E7B-4F2A-9D1E-3C5A7B8F2E4D}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisherURL=https://github.com/ryanDonsi/Light-Stick-Music
AppSupportURL=https://github.com/ryanDonsi/Light-Stick-Music
AppUpdatesURL=https://github.com/ryanDonsi/Light-Stick-Music
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
AllowNoIcons=yes
OutputDir=output
OutputBaseFilename=BeatAccuracyChecker_Setup
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
; 2GB 이상 인스톨러 지원
DiskSpanning=no
LicenseFile=
; 아이콘 (있으면 적용)
; SetupIconFile=icon.ico

[Languages]
Name: "korean";  MessagesFile: "compiler:Languages\Korean.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "바탕화면에 아이콘 만들기"; GroupDescription: "추가 아이콘:"; Flags: unchecked

[Files]
; 메인 실행 파일
Source: "{#DistDir}\{#AppExeName}"; DestDir: "{app}"; Flags: ignoreversion

; beat_analysis_records.json (있으면 포함, 없으면 무시)
Source: "{#ToolsDir}\beat_analysis_records.json"; DestDir: "{app}"; Flags: ignoreversion skipifsourcedoesntexist

; Beat-Transformer 폴더 전체 포함
Source: "{#ToolsDir}\Beat-Transformer\*"; DestDir: "{app}\Beat-Transformer"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}";        Filename: "{app}\{#AppExeName}"
Name: "{group}\제거";               Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}";  Filename: "{app}\{#AppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "{#AppName} 실행"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}\Beat-Transformer"
Type: files;          Name: "{app}\beat_analysis_records.json"
Type: files;          Name: "{app}\.bt_path"
