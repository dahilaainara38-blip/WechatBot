package com.demo.WechatBot.service;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security 登录主体，额外携带不可变的管理账号 ID。
 */
public final class AdminUserPrincipal implements UserDetails {

    private final AdminUserStore.AdminUser user;

    public AdminUserPrincipal(AdminUserStore.AdminUser user) {
        this.user = user;
    }

    public String getId() {
        return user.id();
    }

    public String getRole() {
        return user.role();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.role()));
    }

    @Override
    public String getPassword() {
        return user.passwordHash();
    }

    @Override
    public String getUsername() {
        return user.username();
    }

    @Override
    public boolean isEnabled() {
        return user.enabled();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
