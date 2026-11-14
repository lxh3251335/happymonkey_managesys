package cn.tianshang.commerce.auth;

import cn.tianshang.commerce.common.Database;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService users(Database db) {
        return username -> {
            var rows = db.jdbc.queryForList("SELECT * FROM app_user WHERE username=? AND enabled=TRUE", username);
            if (rows.isEmpty()) throw new UsernameNotFoundException("账户不存在");
            var row = rows.get(0);
            return User.withUsername(username)
                .password(row.get("password_hash").toString())
                .roles(row.get("role").toString())
                .build();
        };
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(a ->
                a
                    .requestMatchers("/api/auth/csrf", "/api/auth/login", "/api/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            )
            .csrf(c -> c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
            .formLogin(f ->
                f
                    .loginProcessingUrl("/api/auth/login")
                    .successHandler((req, res, auth) -> {
                        res.setContentType("application/json;charset=UTF-8");
                        res.getWriter().write("{\"ok\":true}");
                    })
                    .failureHandler((req, res, e) -> {
                        res.setStatus(401);
                        res.setContentType("application/json;charset=UTF-8");
                        res.getWriter().write("{\"message\":\"用户名或密码错误\"}");
                    })
            )
            .logout(l -> l.logoutUrl("/api/auth/logout").logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
            .exceptionHandling(e ->
                e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"message\":\"请先登录\"}");
                })
            );
        return http.build();
    }
}
