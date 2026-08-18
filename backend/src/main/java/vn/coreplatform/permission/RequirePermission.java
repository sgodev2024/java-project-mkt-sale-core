package vn.coreplatform.permission;

import java.lang.annotation.*;

/**
 * PEP trung tâm (E4-S03): khai báo quyền cần thiết ngay trên endpoint; interceptor kiểm tra
 * trước khi controller chạy. Đây là lớp bảo vệ thứ hai — controller vẫn phải tự kiểm tra
 * quyền mức record (annotation không phải lớp bảo vệ duy nhất).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {
  String resource();
  String action();
}
