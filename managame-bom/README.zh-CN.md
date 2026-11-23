[English](README.md) | 中文

# managame-bom

统一所有 managame 组件的版本，以及 network、runtime、rpc 共用的 Netty 模块的版本。同时使用多个组件的业务导入一次，之后
引用组件时不写版本号，这样各组件一定来自同一次发布、跑在同一个 Netty 上：

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>cn.managame</groupId>
      <artifactId>managame-bom</artifactId>
      <version>1.0.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>cn.managame</groupId>
    <artifactId>managame-runtime</artifactId>
  </dependency>
  <dependency>
    <groupId>cn.managame</groupId>
    <artifactId>managame-rpc</artifactId>
  </dependency>
</dependencies>
```

它只有版本号：没有代码，也没有组件依赖它，所以每个组件仍然可以单独使用。只用一个组件的业务不需要它。
