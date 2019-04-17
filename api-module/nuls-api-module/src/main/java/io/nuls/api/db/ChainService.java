package io.nuls.api.db;

import io.nuls.api.model.po.db.ChainInfo;
import io.nuls.api.model.po.db.SyncInfo;

import java.util.List;

public interface ChainService {

    void initCache();

    List<ChainInfo> getChainInfoList();

    void addChainInfo(ChainInfo chainInfo);

    ChainInfo getChainInfo(int chainId);

    SyncInfo saveNewSyncInfo(int chainId, long newHeight);

    void updateStep(SyncInfo syncInfo);
}
