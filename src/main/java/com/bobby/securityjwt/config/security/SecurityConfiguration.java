package com.bobby.securityjwt.config.security;

import com.bobby.securityjwt.common.AjaxResult;
import com.bobby.securityjwt.common.Const;
import com.bobby.securityjwt.config.security.filter.JwtAuthenticationFilter;
import com.bobby.securityjwt.config.security.filter.RequestLogFilter;
import com.bobby.securityjwt.service.MyUserDetailService;
import com.bobby.securityjwt.service.UserService;
import com.bobby.securityjwt.util.JwtUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * SpringSecurity相关配置
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity//开启注解权限配置
public class SecurityConfiguration {

    @Resource
    JwtAuthenticationFilter jwtAuthenticationFilter;

    @Resource
    RequestLogFilter requestLogFilter;

    @Resource
    JwtUtils utils;

    @Resource
    UserService userService;

    @Resource
    MyUserDetailService myUserDetailService;


//    @Bean
//    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
//        return authenticationConfiguration.getAuthenticationManager();
//    }


    /**
     * 针对于 SpringSecurity 6 的新版配置方法
     *
     * @param http 配置器
     * @return 自动构建的内置过滤器链
     * @throws Exception 可能的异常
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        log.info("SecurityFilterChain");
        return http
                .userDetailsService(myUserDetailService)
                .authorizeHttpRequests(conf -> conf
                                .requestMatchers("/pwd/reset").permitAll()  // 修改密码测试
                                .requestMatchers("/login").permitAll()
                                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                                .anyRequest().hasAnyRole(Const.ROLE_DEFAULT)
                        // hasAnyRole 会自动在role名称前添加"ROLE_"
                        // 注意自己数据库中的 role
                )
                .formLogin(conf -> conf
                        // 登录接口，不需要自己再写controller
                        // 登录采用Params username & password
                        .loginProcessingUrl("/login")
                        .failureHandler(this::handleProcess)
                        .successHandler(this::handleProcess)
                        .permitAll()
                )
                .logout(conf -> conf
                        .logoutUrl("/logout")
                        .logoutSuccessHandler(this::onLogoutSuccess)
                )
                .exceptionHandling(conf -> conf
                        .accessDeniedHandler(this::handleProcess)
                        .authenticationEntryPoint(this::handleProcess)
                )
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(conf -> conf
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(requestLogFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, RequestLogFilter.class)   // 验证token
                .build();
    }

    /**
     * 将多种类型的Handler整合到同一个方法中，包含：
     * - 登录成功
     * - 登录失败
     * - 未登录拦截/无权限拦截
     *
     * @param request                   请求
     * @param response                  响应
     * @param exceptionOrAuthentication 异常或是验证实体
     * @throws IOException 可能的异常
     */
    private void handleProcess(HttpServletRequest request,
                               HttpServletResponse response,
                               Object exceptionOrAuthentication) throws IOException {
        response.setContentType(Const.CONTENT_TYPE);
        PrintWriter writer = response.getWriter();
        if (exceptionOrAuthentication instanceof AccessDeniedException exception) { // 访问拒绝
            /**
             * 角色认证不通过时，可能产生访问拒绝
             */
            writer.write(AjaxResult.error(AjaxResult.HttpStatus.FORBIDDEN, exception.getMessage()).asJsonString());
        } else if (exceptionOrAuthentication instanceof Exception exception) {
            // 异常
            writer.write(AjaxResult.error(AjaxResult.HttpStatus.FORBIDDEN, exception.getMessage()).asJsonString());
        } else if (exceptionOrAuthentication instanceof Authentication authentication) {
            // 认证
            UserDetails authUser = (UserDetails) authentication.getPrincipal();
            com.bobby.securityjwt.entity.User user = userService.selectByUsername(authUser.getUsername());
            // 利用用户名和ID生成token
            String jwt = utils.createJwt(authUser, user.getUsername(), user.getId());
            if (jwt == null) {
                writer.write(AjaxResult.error(AjaxResult.HttpStatus.FORBIDDEN, "登录验证频繁，请稍后再试").asJsonString());
            } else {
                // token 写到Header
                response.setHeader(Const.HEADER, "Bearer " + jwt);

                AjaxResult ajax = AjaxResult.success("登录成功");
                ajax.put("username", user.getUsername());
                ajax.put("token", jwt);
                ajax.put("expire", utils.expireTime());
                writer.write(ajax.asJsonString());
            }
        }
    }

    /**
     * 退出登录处理，将对应的Jwt令牌列入黑名单不再使用
     *
     * @param request        请求
     * @param response       响应
     * @param authentication 验证实体
     * @throws IOException 可能的异常
     */
    private void onLogoutSuccess(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Authentication authentication) throws IOException {
        response.setContentType("application/json;charset=utf-8");
        PrintWriter writer = response.getWriter();
        String authorization = request.getHeader(Const.HEADER);
        if (utils.invalidateJwt(authorization)) {   // 登出时，使jwt失效
            writer.write(AjaxResult.success("退出登录成功").asJsonString());
            return;
        }
        writer.write(AjaxResult.error(AjaxResult.HttpStatus.BAD_REQUEST, "退出登录失败").asJsonString());
    }

//    @Bean
//    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
//        return authenticationConfiguration.getAuthenticationManager();
//    }
}
