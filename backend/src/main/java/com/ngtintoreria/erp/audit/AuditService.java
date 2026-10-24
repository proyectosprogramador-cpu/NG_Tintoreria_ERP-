package com.ngtintoreria.erp.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class AuditService {
    private final AuditEventRepository repo;
    public AuditService(AuditEventRepository repo){this.repo=repo;}

    public void record(String action,String module,String referenceId,String detail){
        Authentication a=SecurityContextHolder.getContext().getAuthentication();
        String actor=(a!=null&&a.isAuthenticated()&&a.getName()!=null)?a.getName():"SYSTEM";
        recordAs(actor,action,module,referenceId,detail,true);
    }

    public void recordFailure(String action,String module,String referenceId,String detail){
        Authentication a=SecurityContextHolder.getContext().getAuthentication();
        String actor=(a!=null&&a.isAuthenticated()&&a.getName()!=null)?a.getName():"SYSTEM";
        recordAs(actor,action,module,referenceId,detail,false);
    }

    public void recordAs(String actor,String action,String module,String referenceId,String detail,boolean success){
        HttpServletRequest req=currentRequest();
        String ip=req==null?null:clientIp(req);
        String method=req==null?null:req.getMethod();
        String path=req==null?null:req.getRequestURI();
        repo.save(new AuditEvent(trim(actor==null?"UNKNOWN":actor,160),trim(action,100),trim(module,100),
                trim(referenceId,120),detail,trim(ip,80),trim(method,12),trim(path,300),success));
    }

    private HttpServletRequest currentRequest(){
        var attrs=RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes s ? s.getRequest() : null;
    }

    private String trim(String s,int max){return s==null?null:(s.length()<=max?s:s.substring(0,max));}

    private String clientIp(HttpServletRequest req){
        String forwarded=req.getHeader("X-Forwarded-For");
        if(forwarded!=null&&!forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
