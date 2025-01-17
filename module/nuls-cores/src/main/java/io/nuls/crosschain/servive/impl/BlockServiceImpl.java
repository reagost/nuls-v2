package io.nuls.crosschain.servive.impl;

import io.nuls.base.RPCUtil;
import io.nuls.base.data.BlockExtendsData;
import io.nuls.base.data.BlockHeader;
import io.nuls.base.data.NulsHash;
import io.nuls.base.data.Transaction;
import io.nuls.common.NulsCoresConfig;
import io.nuls.core.basic.Result;
import io.nuls.core.constant.CommonCodeConstanst;
import io.nuls.core.core.annotation.Autowired;
import io.nuls.core.core.annotation.Component;
import io.nuls.core.crypto.HexUtil;
import io.nuls.crosschain.constant.NulsCrossChainConstant;
import io.nuls.crosschain.constant.NulsCrossChainErrorCode;
import io.nuls.crosschain.constant.ParamConstant;
import io.nuls.crosschain.model.bo.BroadFailFlag;
import io.nuls.crosschain.model.bo.Chain;
import io.nuls.crosschain.model.po.SendCtxHashPO;
import io.nuls.crosschain.rpc.call.ConsensusCall;
import io.nuls.crosschain.servive.BlockService;
import io.nuls.crosschain.srorage.*;
import io.nuls.crosschain.utils.BroadCtxUtil;
import io.nuls.crosschain.utils.TxUtil;
import io.nuls.crosschain.utils.manager.ChainManager;
import io.nuls.crosschain.utils.thread.VerifierChangeTxHandler;

import java.util.*;

/**
 * 提供给区块模块调用的接口实现类
 * @author tag
 * @date 2019/4/25
 */
@Component
public class BlockServiceImpl implements BlockService {
    @Autowired
    private ChainManager chainManager;

    @Autowired
    private SendHeightService sendHeightService;

    @Autowired
    private SendedHeightService sendedHeightService;

    @Autowired
    private NulsCoresConfig config;

    @Autowired
    private ConvertCtxService convertCtxService;

    @Autowired
    private CtxStatusService ctxStatusService;

    @Autowired
    private VerifierChangeBroadFailedService verifierChangeBroadFailedService;

    @Override
    @SuppressWarnings("unchecked")
    public Result syncStatusUpdate(Map<String, Object> params) {
        if (params.get(ParamConstant.CHAIN_ID) == null || params.get(ParamConstant.SYNC_STATUS) == null) {
            return Result.getFailed(CommonCodeConstanst.PARAMETER_ERROR);
        }
        int chainId = (int) params.get(ParamConstant.CHAIN_ID);
        if (chainId <= 0) {
            return Result.getFailed(CommonCodeConstanst.PARAMETER_ERROR);
        }
        Chain chain = chainManager.getChainMap().get(chainId);
        if (chain == null) {
            return Result.getFailed(NulsCrossChainErrorCode.CHAIN_NOT_EXIST);
        }
        int syncStatus = (int)params.get(ParamConstant.SYNC_STATUS);
        chain.setSyncStatus(syncStatus);
        chain.getLogger().info("节点同步状态变更，syncStatus:{}",syncStatus );
        return Result.getSuccess(CommonCodeConstanst.SUCCESS);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Result newBlockHeight(Map<String, Object> params) {
        Result result = paramValid(params);
        if(result.isFailed()){
            return result;
        }
        int chainId = (int) params.get(ParamConstant.CHAIN_ID);
        Chain chain = chainManager.getChainMap().get(chainId);
        long height = Long.valueOf(params.get(ParamConstant.NEW_BLOCK_HEIGHT).toString());
        chain.getLogger().info("收到区块高度更新信息，最新区块高度为：{}", height);
        //查询是否有待广播的跨链交易
        Map<Long , SendCtxHashPO> sendHeightMap = sendHeightService.getList(chainId);
        if(sendHeightMap != null && sendHeightMap.size() >0){
            Set<Long> sortSet = new TreeSet<>(sendHeightMap.keySet());
            //各条链状态缓存
            Map<Integer,Byte> crossStatusMap = new HashMap<>(NulsCrossChainConstant.INIT_CAPACITY_16);
            //广播到各链失败的各种交易类型缓存，避免广播乱序
            Map<Integer, BroadFailFlag> broadFailMap = new HashMap<>(NulsCrossChainConstant.INIT_CAPACITY_16);
            for (long cacheHeight:sortSet) {
                if(height >= cacheHeight){
                    chain.getLogger().debug("广播区块高度为{}的跨链交易给其他链",cacheHeight );
                    SendCtxHashPO po = sendHeightMap.get(cacheHeight);
                    List<NulsHash> broadSuccessCtxHash = new ArrayList<>();
                    List<NulsHash> broadFailCtxHash = new ArrayList<>();
                    for (NulsHash ctxHash:po.getHashList()) {
                        if(BroadCtxUtil.broadCtxHash(chain, ctxHash, cacheHeight, crossStatusMap,broadFailMap)){
                            broadSuccessCtxHash.add(ctxHash);
                        }else{
                            broadFailCtxHash.add(ctxHash);
                        }
                    }
                    if(broadSuccessCtxHash.size() > 0){
                        SendCtxHashPO sendedPo = sendedHeightService.get(cacheHeight,chainId);
                        if(sendedPo != null){
                            sendedPo.getHashList().addAll(broadSuccessCtxHash);
                        }else{
                            sendedPo = new SendCtxHashPO(broadSuccessCtxHash);
                        }
                        if(!sendedHeightService.save(cacheHeight, sendedPo, chainId)){
                            continue;
                        }
                    }
                    if(broadFailCtxHash.size() > 0){
                        int ONE_DAY_HEIGHT = 360 * 24;
                        if(height - cacheHeight < ONE_DAY_HEIGHT){
                            po.setHashList(broadFailCtxHash);
                            sendHeightService.save(cacheHeight, po, chainId);
                            chain.getLogger().error("区块高度为{}的跨链交易广播失败",cacheHeight);
                        }
                    }else{
                        sendHeightService.delete(cacheHeight, chainId);
                        chain.getLogger().info("区块高度为{}的跨链交易广播成功",cacheHeight);
                    }
                }else{
                    break;
                }
            }
        }
        chain.getLogger().debug("区块高度更新消息处理完成,Height:{}\n\n",height);
        return Result.getSuccess(CommonCodeConstanst.SUCCESS);
    }

    private Result paramValid(Map<String, Object> params){
        if (params.get(ParamConstant.CHAIN_ID) == null || params.get(ParamConstant.NEW_BLOCK_HEIGHT) == null || params.get(ParamConstant.PARAM_BLOCK_HEADER) == null) {
            return Result.getFailed(CommonCodeConstanst.PARAMETER_ERROR);
        }
        int chainId = (int) params.get(ParamConstant.CHAIN_ID);
        int download = (int) params.get("download");
        if (chainId <= 0) {
            return Result.getFailed(CommonCodeConstanst.PARAMETER_ERROR);
        }
        Chain chain = chainManager.getChainMap().get(chainId);
        if (chain == null) {
            return Result.getFailed(NulsCrossChainErrorCode.CHAIN_NOT_EXIST);
        }
        try {
            BlockHeader blockHeader = new BlockHeader();
            String headerHex = (String) params.get(ParamConstant.PARAM_BLOCK_HEADER);
            blockHeader.parse(RPCUtil.decode(headerHex), 0);
            if(!chainManager.isCrossNetUseAble()){
                chainManager.getChainHeaderMap().put(chainId, blockHeader);
                chain.getLogger().info("等待共识网络组网完成");
                return Result.getSuccess(CommonCodeConstanst.SUCCESS);
            }
            if(config.isMainNet() && chainManager.getRegisteredCrossChainList().size() <= 1){
                chain.getLogger().info("当前没有注册链" );
                chainManager.getChainHeaderMap().put(chainId, blockHeader);
                return Result.getSuccess(CommonCodeConstanst.SUCCESS);
            }
            /*
            区块链在运行状态(download 0区块下载中,1接收到最新区块)，检测是否有轮次变化，如果有轮次变化，查询共识模块共识节点是否有变化，如果有变化则创建验证人变更交易(该操作需要在验证人初始化交易之后)
            */
            if(download == 1 && chain.getVerifierList() != null && !chain.getVerifierList().isEmpty()){
                Map<String,List<String>> agentChangeMap;
                BlockHeader localHeader = chainManager.getChainHeaderMap().get(chainId);
                if(localHeader != null){
                    BlockExtendsData blockExtendsData = blockHeader.getExtendsData();
                    BlockExtendsData localExtendsData = localHeader.getExtendsData();
                    if(blockExtendsData.getRoundIndex() == localExtendsData.getRoundIndex()){
                        chainManager.getChainHeaderMap().put(chainId, blockHeader);
                        return Result.getSuccess(CommonCodeConstanst.SUCCESS);
                    }
                    agentChangeMap = ConsensusCall.getAgentChangeInfo(chain, localHeader.getExtend(), blockHeader.getExtend());
                }else{
                    agentChangeMap = ConsensusCall.getAgentChangeInfo(chain, null, blockHeader.getExtend());
                }
                if(agentChangeMap != null){
                    List<String> registerAgentList = agentChangeMap.get(ParamConstant.PARAM_REGISTER_AGENT_LIST);
                    List<String> cancelAgentList = agentChangeMap.get(ParamConstant.PARAM_CANCEL_AGENT_LIST);
                    //第一个区块特殊处理,判断获取的到的变更的验证人列表是否正确
                    if(localHeader == null){
                        if(registerAgentList != null){
                            registerAgentList.removeAll(chain.getVerifierList());
                        }
                    }
                    boolean verifierChange = (registerAgentList != null && !registerAgentList.isEmpty()) || (cancelAgentList != null && !cancelAgentList.isEmpty());
                    if(verifierChange){
                        chain.getLogger().info("有验证人变化，创建验证人变化交易，最新轮次与上一轮共有的出块地址为：{},新增的验证人列表：{},减少的验证人列表：{}", chain.getVerifierList().toString(),registerAgentList,cancelAgentList);
                        Transaction verifierChangeTx = TxUtil.createVerifierChangeTx(registerAgentList, cancelAgentList, blockHeader.getExtendsData().getRoundStartTime(),chainId);
                        chain.getCrossTxThreadPool().execute(new VerifierChangeTxHandler(chain, verifierChangeTx, blockHeader.getHeight()));
                    }
                }
            }
            chainManager.getChainHeaderMap().put(chainId, blockHeader);
        }catch (Exception e){
            chain.getLogger().error(e);
            return Result.getFailed(CommonCodeConstanst.DATA_PARSE_ERROR);
        }
        return Result.getSuccess(CommonCodeConstanst.SUCCESS);
    }
}
