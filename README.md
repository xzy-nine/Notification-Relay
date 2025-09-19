# 通知转发应用
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/xzy-nine/Notification-Relay)
![GitHub Downloads (all assets, latest release)](https://img.shields.io/github/downloads/xzy-nine/Notification-Relay/latest/total)
![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/xzy-nine/Notification-Relay/total)
## 应用简介
本应用可实现多设备间的通知相互转发功能。通过获取设备通知访问权限、应用列表权限及网络权限，设备间可双向转发通知原文。转发消息包含原通知的应用跳转接口，若接收设备安装了对应应用，点击转发消息即可跳转至与发送方一致的应用。

## 开始使用
进入应用后显示欢迎界面,请授权所有的必须权限;
权限使用说明如下:

### 必须权限
- **通知访问权限**: 用于读取通知内容，实现转发功能
- **应用列表权限**: 用于发现本机已安装应用，辅助通知跳转
- **通知发送权限 (Android 13+)**: 用于发送本地通知，部分功能需开启
- **自启动权限**: 必须启用，否则监听服务无法启动

### 可选权限
- **蓝牙连接权限**: 用于优化设备发现速度，显示真实设备名
- **后台无限制权限**: 用于确保应用在后台正常运行，防止被系统杀死
- **悬浮通知权限**: 请手动选择并打开具体的通知类别的悬浮通知权限，以提升通知体验
- **敏感通知访问权限 (Android 15+，可选)**: 未授权时部分通知内容只能获取到'已隐藏敏感通知',因此建议开启以完整接收通知。

