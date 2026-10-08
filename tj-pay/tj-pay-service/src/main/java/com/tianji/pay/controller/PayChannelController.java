package com.tianji.pay.controller;


import com.tianji.common.utils.BeanUtils;
import com.tianji.pay.sdk.dto.PayChannelDTO;
import com.tianji.pay.service.IPayChannelService;
import com.tianji.pay.sdk.constants.PayConstants;
import com.tianji.pay.third.IPayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * <p>
 * 支付渠道 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2022-08-26
 */
@Tag(name = "支付相关接口")
@RestController
@RequestMapping("/pay-channels")
@RequiredArgsConstructor
public class PayChannelController {

    private final IPayChannelService channelService;
    @Autowired(required = false)
    private Map<String, IPayService> payServiceChannels = Collections.emptyMap();

    @Operation(summary = "查询支付渠道列表")
    @GetMapping("/list")
    @ApiResponse(responseCode = "200", description = "支付渠道列表", content = @Content(schema = @Schema(implementation = PayChannelDTO.class)))
    public List<PayChannelDTO> listAllPayChannels(){
        List<PayChannelDTO> channels = BeanUtils.copyList(channelService.list(), PayChannelDTO.class).stream()
                .filter(channel -> payServiceChannels.containsKey(channel.getChannelCode()))
                .filter(channel -> !PayConstants.MOCK_CHANNEL_CODE.equals(channel.getChannelCode()))
                .collect(Collectors.toCollection(ArrayList::new));
        if (payServiceChannels.containsKey(PayConstants.MOCK_CHANNEL_CODE)) {
            // 只在local进程内提供，不修改共享数据库的渠道字典。
            channels.add(PayChannelDTO.builder().name("本地模拟支付").channelCode(PayConstants.MOCK_CHANNEL_CODE)
                    .status(1).channelPriority(0).channelIcon("/favicon.ico").build());
        }
        return channels;
    }

    @Operation(summary = "添加支付渠道")
    @PostMapping
    @ApiResponse(responseCode = "200", description = "支付渠道ID", content = @Content(schema = @Schema(implementation = Long.class)))
    public Long addPayChannel(@Valid @RequestBody @Schema(description = "支付渠道信息") PayChannelDTO channelDTO){
        return channelService.addPayChannel(channelDTO);
    }

    @Operation(summary = "修改支付渠道")
    @PutMapping("/{id}")
    @ApiResponse(responseCode = "200", description = "修改结果")
    public void updatePayChannel(
            @Parameter(description = "支付渠道id") @PathVariable("id") Long id,
            @RequestBody @Schema(description = "支付渠道信息") PayChannelDTO channelDTO){
        channelDTO.setId(id);
        channelService.updatePayChannel(channelDTO);
    }
}
