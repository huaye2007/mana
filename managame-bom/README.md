English | [中文](README.zh-CN.md)

# managame-bom

One version for every managame component, and for the Netty modules network, runtime and rpc share. An application
that takes several components imports it once and names the components without versions, so they always come from
one release and run on one Netty:

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

It holds versions only: no code, and no component depends on it, so each component still works on its own. An
application that takes one component needs no BOM.
