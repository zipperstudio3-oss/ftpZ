# ftpZ

----------------------------------- Hey, its me zunaed ---------------------------------------

Turn your Android phone into an FTP server with one tap. Share photos, videos and files with your PC over Wi-Fi, no cables.

- Android 8.0 (API 26) and newer
- No third-party libraries (pure Android SDK + a built-in FTP engine)
- Dark UI with a glowing power button, live stats and activity log
- Adaptive launcher icon (with themed-icon support on Android 13+)

## Using the app

1. Join the same Wi-Fi as your PC (or turn on your phone's hotspot and connect the PC to it).
2. Open ftpZ and tap the power button. The first time, allow storage access.
3. On your PC, paste the address shown in the app (for example `ftp://192.168.1.20:2121`) into Windows File Explorer, or use FileZilla / WinSCP.
4. Log in with the username and password shown in the app. A random password is created on first run.

Settings: port, username, password, shared folder (quick chips for Download / DCIM / Documents / Pictures / Music), read-only mode, anonymous mode.

## Security notes

- Plain FTP is not encrypted. Use it on networks you trust, and stop the server when you are done.
- Anonymous mode lets anyone on your network access the shared folder.
- Read-only mode blocks uploads, deletes, renames and new folders.

## Project layout

```
app/src/main/java/com/ftpz/app/
  FtpServer.java      FTP engine (pure Java)
  FtpService.java     foreground service that keeps the server running
  MainActivity.java   the screen
  NetUtil.java        finds the phone's Wi-Fi / hotspot IP
  Prefs.java          saved settings
app/src/main/res/     layouts, drawables, adaptive icon
ftpZ-icon-512.png     the icon as a 512 px image
```

Supported FTP commands: USER, PASS, PWD, CWD, CDUP, LIST, NLST, MLSD, MLST, RETR, STOR, APPE, DELE, MKD, RMD, RNFR, RNTO, SIZE, MDTM, REST, PASV, EPSV, PORT, EPRT, TYPE, FEAT, SYST, NOOP, QUIT.
