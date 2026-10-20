package com.shipora.pipeline;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;
public final class PipelineRequestGuard extends OncePerRequestFilter {
 @Override protected boolean shouldNotFilter(HttpServletRequest request){return !Set.of("POST","PUT","PATCH","DELETE").contains(request.getMethod());}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  if(!"true".equals(request.getHeader("X-Shipora-Request"))){response.setStatus(403);response.setContentType("application/problem+json");response.setHeader("Cache-Control","no-store");response.getWriter().write("{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"X-Shipora-Request: true is required.\"}");return;}
  chain.doFilter(request,response);
 }
}
