/**
 * MIT License
 * <p>
 * Copyright (c) 2017-2019 nuls.io
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.nuls.contract.tx.common;

import io.nuls.base.data.BlockHeader;
import io.nuls.base.data.Transaction;
import io.nuls.base.protocol.CommonAdvice;
import io.nuls.base.protocol.ProtocolGroupManager;
import io.nuls.contract.config.ContractContext;
import io.nuls.contract.enums.BlockType;
import io.nuls.contract.helper.ContractHelper;
import io.nuls.contract.manager.ChainManager;
import io.nuls.contract.model.bo.BatchInfoV8;
import io.nuls.contract.model.bo.Chain;
import io.nuls.contract.model.dto.ContractPackageDto;
import io.nuls.contract.model.po.ContractOfflineTxHashPo;
import io.nuls.contract.storage.ContractOfflineTxHashListStorageService;
import io.nuls.contract.tx.v1.CallContractProcessor;
import io.nuls.contract.tx.v8.CallContractProcessorV8;
import io.nuls.contract.util.Log;
import io.nuls.core.constant.TxType;
import io.nuls.core.core.annotation.Autowired;
import io.nuls.core.core.annotation.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author: PierreLuo
 * @date: 2019-05-27
 */
@Component
public class TransactionCommitAdvice implements CommonAdvice {

    @Autowired
    private ContractHelper contractHelper;
    @Autowired
    private ContractOfflineTxHashListStorageService contractOfflineTxHashListStorageService;
    @Autowired
    private CallContractProcessor callContractProcessor;
    @Autowired
    private CallContractProcessorV8 callContractProcessorV8;

    @Override
    public void begin(int chainId, List<Transaction> txList, BlockHeader header) {
        Log.info("height: {} call begin", header != null ? header.getHeight() : 0);
        try {
            ChainManager.chainHandle(chainId, BlockType.VERIFY_BLOCK.type());
            Short currentVersion = ProtocolGroupManager.getCurrentVersion(chainId);
            if(currentVersion >= ContractContext.UPDATE_VERSION_CONTRACT_ASSET) {
                BatchInfoV8 batchInfo = contractHelper.getChain(chainId).getBatchInfoV8();
                if (batchInfo != null) {
                    Log.info("height: {}, contract execute txDataSize is {}, commit txDataSize is {}", header != null ? header.getHeight() : 0, batchInfo.getContractResultMap().size(), txList.size());

                    List<byte[]> offlineTxHashList = batchInfo.getOfflineTxHashList();
                    if(offlineTxHashList != null && !offlineTxHashList.isEmpty()) {
                        // 保存智能合约链下交易hash
                        contractOfflineTxHashListStorageService.saveOfflineTxHashList(chainId, header.getHash().getBytes(), new ContractOfflineTxHashPo(offlineTxHashList));
                    }
                }
            } else {
                ContractPackageDto contractPackageDto = contractHelper.getChain(chainId).getBatchInfo().getContractPackageDto();
                if (contractPackageDto != null) {
                    Log.info("height: {}, contract execute txDataSize is {}, commit txDataSize is {}", header != null ? header.getHeight() : 0, contractPackageDto.getContractResultMap().keySet().size(), txList.size());

                    List<byte[]> offlineTxHashList = contractPackageDto.getOfflineTxHashList();
                    if(offlineTxHashList != null && !offlineTxHashList.isEmpty()) {
                        // 保存智能合约链下交易hash
                        contractOfflineTxHashListStorageService.saveOfflineTxHashList(chainId, header.getHash().getBytes(), new ContractOfflineTxHashPo(contractPackageDto.getOfflineTxHashList()));
                    }
                }
            }
            // add by pierre at 2019-12-01 处理type10交易的业务提交, 需要协议升级 done
            if(currentVersion >= ContractContext.UPDATE_VERSION_V250) {
                List<Transaction> crossTxList = txList.stream().filter(tx -> tx.getType() == TxType.CROSS_CHAIN).collect(Collectors.toList());
                if(currentVersion >= ContractContext.UPDATE_VERSION_CONTRACT_ASSET) {
                    callContractProcessorV8.commit(chainId, crossTxList, header);
                } else {
                    callContractProcessor.commit(chainId, crossTxList, header);
                }
            }
            // end code by pierre
        } catch (Exception e) {
            Log.error(e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void end(int chainId, List<Transaction> txList, BlockHeader blockHeader) {
        Log.info("height: {} call end", blockHeader != null ? blockHeader.getHeight() : 0);
        // 移除临时余额, 临时区块头等当前批次执行数据
        Chain chain = contractHelper.getChain(chainId);
        if(ProtocolGroupManager.getCurrentVersion(chainId) >= ContractContext.UPDATE_VERSION_CONTRACT_ASSET ) {
            chain.setBatchInfoV8(null);
        } else {
            chain.setBatchInfo(null);
        }
    }
}
