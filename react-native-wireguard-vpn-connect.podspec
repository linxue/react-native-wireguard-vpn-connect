Pod::Spec.new do |s|
  s.name         = "react-native-wireguard-vpn-connect"
  s.version      = "1.0.15" # 与你安装的库版本一致
  s.summary      = "React Native WireGuard VPN Connect"
  s.homepage     = "https://github.com/相关仓库地址" # 可留空
  s.license      = "MIT"
  s.author       = { "Author" => "author@example.com" } # 可留空
  s.platform     = :ios, "13.0" # 匹配 RN 0.84 最低 iOS 版本
  s.source       = { :path => __dir__ }
  s.source_files = "ios/*.{h,m,swift}" # 匹配库的 iOS 原生代码
  s.requires_arc = true

  # 依赖 React Native 核心库
  s.dependency "React-Core"
end