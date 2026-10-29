package com.demo.WechatBot.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminUserStore userStore;

    public AdminUserDetailsService(AdminUserStore userStore) {
        this.userStore = userStore;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AdminUserStore.AdminUser user = userStore.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户名或密码错误"));
        return new AdminUserPrincipal(user);
    }
}
