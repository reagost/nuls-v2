package io.nuls.contract.tx.v14;

import io.nuls.base.data.BlockHeader;
import io.nuls.base.data.Transaction;
import io.nuls.base.protocol.TransactionProcessor;
import io.nuls.contract.helper.ContractHelper;
import io.nuls.contract.manager.ChainManager;
import io.nuls.contract.model.bo.BatchInfoV8;
import io.nuls.contract.model.bo.ContractResult;
import io.nuls.contract.model.bo.ContractWrapperTransaction;
import io.nuls.contract.model.tx.CreateContractTransaction;
import io.nuls.contract.model.txdata.CreateContractData;
import io.nuls.contract.processor.CreateContractTxProcessor;
import io.nuls.contract.util.Log;
import io.nuls.contract.validator.CreateContractTxValidator;
import io.nuls.core.basic.Result;
import io.nuls.core.constant.TxType;
import io.nuls.core.core.annotation.Autowired;
import io.nuls.core.core.annotation.Component;
import io.nuls.core.exception.NulsException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// add by pierre at 2022/6/6 p14
@Component("CreateContractProcessorV14")
public class CreateContractProcessorV14 implements TransactionProcessor {

    @Autowired
    private CreateContractTxProcessor createContractTxProcessor;
    @Autowired
    private CreateContractTxValidator createContractTxValidator;
    @Autowired
    private ContractHelper contractHelper;

    @Override
    public int getType() {
        return TxType.CREATE_CONTRACT;
    }

    @Override
    public Map<String, Object> validate(int chainId, List<Transaction> txs, Map<Integer, List<Transaction>> txMap, BlockHeader blockHeader) {
        ChainManager.chainHandle(chainId);
        Map<String, Object> result = new HashMap<>();
        List<Transaction> errorList = new ArrayList<>();
        result.put("txList", errorList);
        String errorCode = null;
        CreateContractTransaction createTx;
        for(Transaction tx : txs) {
            createTx = new CreateContractTransaction();
            createTx.copyTx(tx);
            try {
                Result validate = createContractTxValidator.validate(chainId, createTx);
                if(validate.isFailed()) {
                    errorCode = validate.getErrorCode().getCode();
                    errorList.add(tx);
                }
            } catch (NulsException e) {
                Log.error(e);
                errorCode = e.getErrorCode().getCode();
                errorList.add(tx);
            }
        }
        result.put("errorCode", errorCode);
        return result;
    }

    @Override
    public boolean commit(int chainId, List<Transaction> txs, BlockHeader header) {
        try {
            BatchInfoV8 batchInfo = contractHelper.getChain(chainId).getBatchInfoV8();
            if (batchInfo != null) {
                Map<String, ContractResult> contractResultMap = batchInfo.getContractResultMap();
                ContractResult contractResult;
                ContractWrapperTransaction wrapperTx;
                String txHash;
                for (Transaction tx : txs) {
                    txHash = tx.getHash().toString();
                    contractResult = contractResultMap.get(txHash);
                    if (contractResult == null) {
                        Log.warn("empty contract result with txHash: {}", txHash);
                        continue;
                    }
                    wrapperTx = contractResult.getTx();
                    wrapperTx.setContractResult(contractResult);
                    createContractTxProcessor.onCommitV14(chainId, wrapperTx);
                }
            }

            return true;
        } catch (Exception e) {
            Log.error(e);
            return false;
        }
    }

    @Override
    public boolean rollback(int chainId, List<Transaction> txs, BlockHeader blockHeader) {
        try {
            ChainManager.chainHandle(chainId);
            CreateContractData create;
            for (Transaction tx : txs) {
                create = new CreateContractData();
                create.parse(tx.getTxData(), 0);
                createContractTxProcessor.onRollbackV14(chainId, new ContractWrapperTransaction(tx, create));
            }
            return true;
        } catch (Exception e) {
            Log.error(e);
            return false;
        }
    }
}