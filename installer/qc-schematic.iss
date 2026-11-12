; Inno Setup script for the QC Schematic Windows installer.
;
; Build the release binary first, then compile this script:
;
;   cargo build --release
;   iscc installer\qc-schematic.iss
;
; The installer is written to target\installer\qc-schematic-setup-<version>.exe.
; The version is read from the version resource of the built qc-schematic.exe,
; which in turn comes from the workspace Cargo.toml (see crates/qc-app/build.rs).

#define MyAppName "QC Schematic"
#define MyAppExeName "qc-schematic.exe"
#define MyAppVersion GetVersionNumbersString("..\target\release\qc-schematic.exe")

[Setup]
AppId={{47995A2C-297A-4FBD-93E5-845B051767CD}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher=Sebastian Mihai ARDELEAN
AppPublisherURL=https://github.com/sebastianardelean/qc-schematic
LicenseFile=..\LICENSE
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir=..\target\installer
OutputBaseFilename=qc-schematic-setup-{#MyAppVersion}
SetupIconFile=..\build\qc-schematic.ico
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible
VersionInfoVersion={#MyAppVersion}
VersionInfoProductName={#MyAppName}
VersionInfoProductVersion={#MyAppVersion}
VersionInfoCompany=Sebastian Mihai ARDELEAN
VersionInfoDescription={#MyAppName} Setup
UninstallDisplayIcon={app}\{#MyAppExeName}

[Files]
Source: "..\target\release\qc-schematic.exe"; DestDir: "{app}"; Flags: ignoreversion

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent
