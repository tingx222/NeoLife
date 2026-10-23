package com.hmdp.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.Shop;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class CustomerTools {
    private final IShopService shops;
    private final IVoucherOrderService orders;
    private final ObjectMapper mapper;
    @javax.annotation.Resource private com.hmdp.service.IVoucherService vouchers;
    public CustomerTools(IShopService shops, IVoucherOrderService orders, ObjectMapper mapper) {
        this.shops = shops; this.orders = orders; this.mapper = mapper;
    }

    public JsonNode definitions() {
        List<Object> definitions = new ArrayList<>();
        definitions.add(tool("search_shops", "按店名关键字查询至多5个商户，返回地址、营业时间和均价", "keyword", "string"));
        definitions.add(tool("get_my_order", "查询当前登录用户自己的订单，不存在与无权查看返回相同结果", "orderId", "string"));
        definitions.add(tool("get_faq", "查询平台登录、秒杀、退款等常见问题", "topic", "string"));
        definitions.add(tool("get_shop_vouchers", "根据商户ID查询公开优惠券及活动信息", "shopId", "string"));
        return mapper.valueToTree(definitions);
    }

    private Map<String, Object> tool(String name, String description, String parameter, String type) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.singletonMap(parameter, Collections.singletonMap("type", type)));
        schema.put("required", Collections.singletonList(parameter));
        schema.put("additionalProperties", false);
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name); function.put("description", description); function.put("parameters", schema);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "function"); result.put("function", function);
        return result;
    }

    public String execute(String name, String arguments, Long userId) {
        try {
            JsonNode args = mapper.readTree(arguments);
            if("get_shop_vouchers".equals(name)){
                String id=text(args,"shopId",19);if(!id.matches("[1-9][0-9]{0,18}"))return "工具参数无效";
                return mapper.writeValueAsString(vouchers.queryVoucherOfShop(Long.parseLong(id)).getData());
            }
            if ("search_shops".equals(name)) {
                String keyword = text(args, "keyword", 50);
                List<Shop> found = shops.list(new QueryWrapper<Shop>().select("id", "name", "address", "open_hours", "avg_price")
                        .like("name", keyword).orderByAsc("id").last("LIMIT 5"));
                return mapper.writeValueAsString(found);
            }
            if ("get_my_order".equals(name)) {
                String id = text(args, "orderId", 19);
                if (!id.matches("[1-9][0-9]{0,18}")) return "订单号格式错误";
                VoucherOrder order = orders.getOne(new QueryWrapper<VoucherOrder>()
                        .select("id", "voucher_id", "status", "create_time")
                        .eq("id", Long.parseLong(id)).eq("user_id", userId));
                return order == null ? "订单不存在或无权查看" : mapper.writeValueAsString(order);
            }
            if ("get_faq".equals(name)) {
                text(args, "topic", 100);
                return "平台使用手机号验证码登录；当前学习项目验证码仅输出开发日志，未接短信服务。"
                        + "秒杀受活动时间、库存和一人一单限制。订单状态：1未支付，2已支付，3已核销，4已取消，5退款中，6已退款。"
                        + "支付、退款、核销流程尚未实现；客服只能查询，不能代抢、支付或退款。";
            }
            return "不支持的工具";
        } catch (IllegalArgumentException | java.io.IOException e) {
            return "工具参数无效，请核对后重试";
        }
    }

    private String text(JsonNode args, String key, int max) {
        JsonNode value = args == null ? null : args.get(key);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()
                || value.asText().length() > max) throw new IllegalArgumentException("Invalid argument");
        return value.asText().trim();
    }
}
