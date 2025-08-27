# Streamify - Android VNC Server

Streamify is an Android application that turns your Android device into a VNC server, allowing you to stream your screen over a local network. This is perfect for monitoring your Android device from a computer or other devices on the same network.

## Features

- **Real-time Screen Streaming**: Stream your Android device's screen in real-time
- **VNC Protocol Support**: Uses standard VNC protocol for maximum compatibility
- **Network Streaming**: Stream over your local network (WiFi/LAN)
- **Easy to Use**: Simple one-tap start/stop interface
- **Port 5900**: Standard VNC port for easy connection

## Requirements

- Android 7.0 (API level 24) or higher
- Device with screen recording capabilities
- Local network connection (WiFi recommended)
- Python with vncdotool for client connection

## Installation

1. Clone or download this repository
2. Open the project in Android Studio
3. Build and install the APK on your Android device
4. Grant necessary permissions when prompted

## Usage

### Starting the VNC Server

1. Open the Streamify app on your Android device
2. Tap "Start VNC Server"
3. Grant screen recording permission when prompted
4. The app will show your device's IP address and port (5900)

### Connecting from Python

Use the provided Python script to connect to your Android device:

```python
from vncdotool import api
from tkinter import Tk, Label
from PIL import Image, ImageTk
from threading import Thread
import os

# Replace 'YOUR_ANDROID_IP' with the IP address shown in the app
client = api.connect('YOUR_ANDROID_IP:5900')

root = Tk()
root.title("Streamify")
root.geometry("320x700")

label = Label(root, bd=0)
label.pack()
old_img = None
tmpfile = "_.png"

def update_screen():
    global old_img
    while True:
        client.captureScreen(tmpfile)
        if os.path.exists(tmpfile):
            img = Image.open(tmpfile)
            if img != old_img:
                img = img.resize((320, 700), Image.Resampling.LANCZOS)
                tk_img = ImageTk.PhotoImage(img)
                label.config(image=tk_img)
                label.image = tk_img
                old_img = img

Thread(target=update_screen, daemon=True).start()
root.mainloop()
os.remove(tmpfile)
```

### Important Notes

- **IP Address**: Use the IP address displayed in the Streamify app, not 127.0.0.1
- **Port**: The server runs on port 5900 (standard VNC port)
- **Network**: Both devices must be on the same local network
- **Permissions**: The app requires screen recording permission to function

## Troubleshooting

### Connection Issues

1. **Check Network**: Ensure both devices are on the same WiFi network
2. **Firewall**: Check if your network has firewall restrictions on port 5900
3. **IP Address**: Verify you're using the correct IP address from the app
4. **Port**: Ensure port 5900 is not blocked or in use by another application

### Performance Issues

1. **Network Speed**: Use a fast WiFi connection for better performance
2. **Device Performance**: Higher-end devices will provide smoother streaming
3. **Resolution**: The app streams at your device's native resolution

### App Issues

1. **Permissions**: Ensure all required permissions are granted
2. **Restart**: Try stopping and restarting the VNC server
3. **Reboot**: Restart your Android device if issues persist

## Security Considerations

- **Local Network Only**: The VNC server only accepts connections from your local network
- **No Authentication**: The current implementation doesn't require a password
- **Network Security**: Ensure your local network is secure

## Technical Details

- **Protocol**: VNC (Virtual Network Computing) RFB protocol
- **Encoding**: Raw RGB encoding for maximum compatibility
- **Service**: Runs as a foreground service with notification
- **Port**: 5900 (configurable in the code)

## Development

### Building from Source

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle files
4. Build the project

### Dependencies

- AndroidX Core KTX
- Jetpack Compose
- Material Design 3
- Android Media APIs

## License

This project is open source and available under the MIT License.

## Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues for bugs and feature requests.

## Support

If you encounter any issues or have questions, please open an issue in the GitHub repository.
