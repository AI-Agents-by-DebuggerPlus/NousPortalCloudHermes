' Silent launcher: starts AHCC Desktop without keeping a console window open.
Option Explicit
Dim sh, dir, bat, cmd
Set sh = CreateObject("WScript.Shell")
dir = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
bat = dir & "\Launch-AHCC-Desktop.bat"
cmd = "cmd /c """ & bat & """"
sh.CurrentDirectory = dir
sh.Run cmd, 0, False
