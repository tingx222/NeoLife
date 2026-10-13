package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private com.hmdp.service.AsyncSeckillService seckill;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {

        return seckill.submit(voucherId);
    }
    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    public Result status(@PathVariable Long id){return seckill.status(id);}
    @org.springframework.web.bind.annotation.GetMapping("/of/me")
    public Result mine(@org.springframework.web.bind.annotation.RequestParam(defaultValue="1") int current){
        if(current<1)return Result.fail("页码无效");
        java.util.List<com.hmdp.entity.VoucherOrder> records=voucherOrderService.query()
                .eq("user_id",com.hmdp.utils.UserHolder.getUser().getId()).orderByDesc("id")
                .page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current,10)).getRecords();
        // Serialize IDs as strings for browsers; JavaScript numbers cannot represent these IDs exactly.
        java.util.List<java.util.Map<String,Object>> data=new java.util.ArrayList<>();
        for(com.hmdp.entity.VoucherOrder order:records){java.util.Map<String,Object> row=new java.util.LinkedHashMap<>();
            row.put("id",order.getId().toString());row.put("voucherId",order.getVoucherId());row.put("status",order.getStatus());row.put("createTime",order.getCreateTime());data.add(row);}
        return Result.ok(data);
    }
}
