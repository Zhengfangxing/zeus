这是一个非常标准的、高内聚低耦合的企业级 Spring Boot 工程结构。

为了满足你的要求，我引入了 **JPA Auditing** 来自动处理创建时间/更新时间，并使用了标准的 `Entity` -\> `Repository` -\> `Service(Interface)` -\> `ServiceImpl` -\> `Controller` 分层结构。

-----

### 1\. 项目结构概览

```text
com.company.project
├── config              // 配置类 (Caffeine, WebMvc, JPA Audit)
├── controller          // 控制层 (Admin API, User API)
├── dto                 // 数据传输对象 (Request/Response)
├── entity              // 数据库实体 (FeatureToggle, BaseEntity)
├── interceptor         // 拦截器 (Global Interceptor)
├── repository          // DAO层 (Spring Data JPA)
├── service             // 业务接口
│   └── impl            // 业务实现 (Caffeine逻辑在这里)
└── util                // 工具类 (UserContext解析)
```

-----

### 2\. Entity (实体层)

我们先定义一个基类来处理审计字段（创建人、时间等），然后定义核心业务实体。

**BaseEntity.java** (公共审计父类)

```java
package com.company.project.entity;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.Column;
import javax.persistence.EntityListeners;
import javax.persistence.MappedSuperclass;
import java.time.LocalDateTime;

@Data
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class) // 开启自动审计
public abstract class BaseEntity {

    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

**FeatureToggle.java** (核心业务表)

```java
package com.company.project.entity;

import lombok.*;
import javax.persistence.*;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
@Entity
@Table(name = "feature_toggles")
@NoArgsConstructor
@AllArgsConstructor
public class FeatureToggle extends BaseEntity {

    @Id
    @Column(name = "feature_key", length = 50)
    private String featureKey;

    @Column(name = "is_enabled", nullable = false)
    private boolean isEnabled;

    @Column(name = "description")
    private String description;

    // 使用 JPA @ElementCollection 优雅处理一对多关系
    // 数据库会生成表：feature_allowed_groups (feature_key, group_name)
    @ElementCollection(fetch = FetchType.EAGER) 
    @CollectionTable(
            name = "feature_allowed_groups",
            joinColumns = @JoinColumn(name = "feature_key")
    )
    @Column(name = "group_name")
    private Set<String> allowedGroups = new HashSet<>();

    /**
     * 核心业务逻辑：判断组是否在白名单中
     * 逻辑：白名单为空 = 全员开放；白名单不为空 = 必须命中其一
     */
    public boolean isGroupAllowed(List<String> userGroups) {
        if (this.allowedGroups == null || this.allowedGroups.isEmpty()) {
            return true; // 全员开放
        }
        if (userGroups == null || userGroups.isEmpty()) {
            return false; // 有门槛，但用户无身份
        }
        // 取交集
        return userGroups.stream().anyMatch(this.allowedGroups::contains);
    }
}
```

-----

### 3\. Repository / DAO 层

**FeatureToggleRepository.java**

```java
package com.company.project.repository;

import com.company.project.entity.FeatureToggle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FeatureToggleRepository extends JpaRepository<FeatureToggle, String> {
    // Spring Data JPA 自动提供 findById, save, delete 等方法
}
```

-----

### 4\. Service 层 (接口 + 实现)

**FeatureService.java** (接口)

```java
package com.company.project.service;

import com.company.project.util.UserContext;
import java.util.List;

public interface FeatureService {
    /**
     * 判断某功能是否对当前用户开放 (读缓存)
     */
    boolean isAllowed(String featureKey, UserContext user);

    /**
     * Admin 更新或创建功能开关 (写数据库 + 清缓存)
     */
    void updateFeature(String key, boolean enabled, List<String> groups, String operator);
}
```

**FeatureServiceImpl.java** (实现类 - 包含 Caffeine)

```java
package com.company.project.service.impl;

import com.company.project.entity.FeatureToggle;
import com.company.project.repository.FeatureToggleRepository;
import com.company.project.service.FeatureService;
import com.company.project.util.UserContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor // Lombok 自动生成构造器注入
public class FeatureServiceImpl implements FeatureService {

    private final FeatureToggleRepository featureRepo;

    // 本地缓存配置：1分钟过期
    private final Cache<String, FeatureToggle> localCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

    @Override
    public boolean isAllowed(String featureKey, UserContext user) {
        // 1. Admin 特权通道
        if (user != null && user.isAdmin()) {
            return true;
        }

        // 2. 从缓存获取配置 (Cache-Aside 模式变体)
        FeatureToggle toggle = localCache.get(featureKey, key -> 
            featureRepo.findById(key).orElse(null)
        );

        // 3. 判空或总开关关闭
        if (toggle == null || !toggle.isEnabled()) {
            return false;
        }

        // 4. 组策略判断
        List<String> groups = (user != null) ? user.getGroups() : null;
        return toggle.isGroupAllowed(groups);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateFeature(String key, boolean enabled, List<String> groups, String operator) {
        FeatureToggle toggle = featureRepo.findById(key)
                .orElse(new FeatureToggle());
        
        // 如果是新建，设置 Key 和 创建人
        if (toggle.getFeatureKey() == null) {
            toggle.setFeatureKey(key);
            toggle.setCreatedBy(operator);
        }

        // 更新字段
        toggle.setEnabled(enabled);
        toggle.setUpdatedBy(operator);
        
        // 更新白名单组 (JPA 会处理 feature_allowed_groups 表的 insert/delete)
        if (groups != null) {
            toggle.setAllowedGroups(new HashSet<>(groups));
        } else {
            toggle.getAllowedGroups().clear();
        }

        featureRepo.save(toggle);

        // 核心：更新后立即使缓存失效，保证下次请求查库
        localCache.invalidate(key);
    }
}
```

-----

### 5\. DTO & Context (传输对象与上下文)

**UserContext.java**

```java
package com.company.project.util;

import lombok.Builder;
import lombok.Data;
import javax.servlet.http.HttpServletRequest;
import java.util.List;

@Data
@Builder
public class UserContext {
    private Long userId;
    private boolean isAdmin;
    private List<String> groups;

    public static UserContext fromRequest(HttpServletRequest request) {
        // TODO: 实际项目中这里解析 JWT Token
        // 模拟数据：
        return UserContext.builder()
                .userId(123L)
                .isAdmin(false) // 尝试改为 true 测试 Admin 权限
                .groups(List.of("USER")) 
                .build();
    }
}
```

**FeatureDTO.java**

```java
package com.company.project.dto;

import lombok.Data;
import java.util.List;

public class FeatureDTO {
    
    @Data
    public static class UpdateRequest {
        private String featureKey;
        private boolean enabled;
        private List<String> allowedGroups;
    }

    @Data
    public static class CurrentUserResponse {
        private Long userId;
        private boolean isAdmin;
        private boolean isGlobalServiceOpen; // 前端据此跳转 404
    }
}
```

-----

### 6\. Controller 层

**UserApiController.java** (普通用户接口)

```java
package com.company.project.controller;

import com.company.project.dto.FeatureDTO;
import com.company.project.service.FeatureService;
import com.company.project.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserApiController {

    private final FeatureService featureService;

    @GetMapping("/current")
    public ResponseEntity<FeatureDTO.CurrentUserResponse> getCurrentUser(HttpServletRequest request) {
        UserContext user = UserContext.fromRequest(request);

        FeatureDTO.CurrentUserResponse resp = new FeatureDTO.CurrentUserResponse();
        resp.setUserId(user.getUserId());
        resp.setAdmin(user.isAdmin());
        
        // 核心：在这里把开关状态返回给前端
        boolean isOpen = featureService.isAllowed("GLOBAL_SVC", user);
        resp.setGlobalServiceOpen(isOpen);

        return ResponseEntity.ok(resp);
    }
}
```

**AdminFeatureController.java** (管理员接口)

```java
package com.company.project.controller;

import com.company.project.dto.FeatureDTO;
import com.company.project.service.FeatureService;
import com.company.project.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/admin/features")
@RequiredArgsConstructor
public class AdminFeatureController {

    private final FeatureService featureService;

    @PostMapping("/update")
    public ResponseEntity<String> updateFeature(@RequestBody FeatureDTO.UpdateRequest req, 
                                                HttpServletRequest request) {
        UserContext admin = UserContext.fromRequest(request);
        
        // 简单的权限校验
        if (!admin.isAdmin()) {
            return ResponseEntity.status(403).body("Access Denied");
        }

        featureService.updateFeature(
                req.getFeatureKey(),
                req.isEnabled(),
                req.getAllowedGroups(),
                "ADMIN_" + admin.getUserId() // 记录操作人
        );

        return ResponseEntity.ok("Feature Updated Successfully");
    }
}
```

-----

### 7\. Interceptor (拦截器)

**GlobalAccessInterceptor.java**

```java
package com.company.project.interceptor;

import com.company.project.service.FeatureService;
import com.company.project.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
@RequiredArgsConstructor
public class GlobalAccessInterceptor implements HandlerInterceptor {

    private final FeatureService featureService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        // 1. 白名单放行 (必须包含 Current User 接口)
        if (uri.startsWith("/api/user/current") || uri.startsWith("/login")) {
            return true;
        }

        // 2. 权限判断
        UserContext user = UserContext.fromRequest(request);
        boolean allowed = featureService.isAllowed("GLOBAL_SVC", user);

        if (allowed) {
            return true;
        }

        // 3. 拦截
        response.setStatus(503);
        response.getWriter().write("Service Unavailable");
        return false;
    }
}
```

-----

### 8\. 配置类 (Configuration)

**WebConfig.java**

```java
package com.company.project.config;

import com.company.project.interceptor.GlobalAccessInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableJpaAuditing // 开启 Entity 自动填充时间
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final GlobalAccessInterceptor globalAccessInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(globalAccessInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/error", "/static/**"); // 排除系统路径
    }
}
```

### 总结

这套代码结构具备以下优点：

1.  **规范性**：严格遵守 Entity -\> Repo -\> Service -\> Controller 分层。
2.  **扩展性**：`BaseEntity` 处理了所有表的审计字段；`@ElementCollection` 处理了动态组扩展。
3.  **性能**：Service 层内嵌 Caffeine 缓存，且逻辑处理了 Admin 绕过。
4.  **完整性**：包含了解析 Token 的 Context 模拟和拦截器兜底逻辑。