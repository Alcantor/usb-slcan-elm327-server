# USB Serial Telnet Server
Android application that binds a USB serial converter to a Telnet client

Just connect a USB serial adapter into USB OTG port of your Android device, start this app and connect to it using any Telnet client like
* [JuiceSSH](https://play.google.com/store/apps/details?id=com.sonelli.juicessh) using the same Android device (connect to localhost)
* Telnet client on a computer on the same network (connect over Wi-Fi)

![Screenshot](https://user-images.githubusercontent.com/4236181/170873697-077b8c7a-51e9-4480-bba7-4871280834dc.png)

This method allows to use all console features like colors and special keys. So you can easyly control/install something like network devices with serial port using only your Android device.

![Photo](https://user-images.githubusercontent.com/4236181/170872965-bb14d004-2c14-47fe-b0bd-ba9863f45791.jpg)

## Compatible Devices
This app uses [usb-serial-for-android  library by mik3y](https://github.com/mik3y/usb-serial-for-android) and supports USB to serial converter chips:
* FTDI FT232R, FT232H, FT2232H, FT4232H, FT230X, FT231X, FT234XD
* Prolific PL2303
* Silabs CP2102 and all other CP210x
* Qinheng CH340, CH341A

and devices implementing the CDC/ACM protocol like
* Arduino using ATmega32U4
* Digispark using V-USB software USB
* BBC micro:bit using ARM mbed DAPLink firmware
* ...

