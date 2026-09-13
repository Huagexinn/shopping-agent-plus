package com.example.security;

import com.example.common.Const;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.annotation.Resource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 从 Bearer Token 解析 JWT，并将认证信息放入 SecurityContext。 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    @Resource
    private JwtService jwtService;

    /** 处理当前请求中的 JWT；无效令牌不会建立认证上下文。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(header.substring(7));
                var authentication = new UsernamePasswordAuthenticationToken(
                        claims.getSubject(), null, AuthorityUtils.NO_AUTHORITIES);
                Long userId = ((Number) claims.get("userId")).longValue();
                authentication.setDetails(userId);
                request.setAttribute(Const.ATTR_USER_ID, userId);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * JWT 过滤器需要在 async dispatch 上重跑。
     *
     * <p>SSE 正常结束后 Spring MVC 会对异步请求做一次 ASYNC dispatch。那次 dispatch 的
     * SecurityContextHolder 是空的（无状态会话不会恢复它），而 {@code /api/ai/**} 要求登录，
     * 于是授权层抛 {@code AuthorizationDeniedException}；此时响应已提交，容器只能记下一条
     * status=500 和一段堆栈。</p>
     *
     * <p>这里返回 {@code false} 让过滤器在异步回派时重新从同一个 Authorization 头建立认证，
     * 使异步回派带着与初次请求相同的身份 —— 不放松任何授权规则。普通 JSON 请求不进入 async
     * 模式，过滤器行为与之前完全一致。</p>
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
