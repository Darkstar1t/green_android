package com.greenaddress.greenbits.ui.transactions;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.blockstream.gdk.data.TwoFactorReset;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenaddress.Bridge;
import com.greenaddress.greenapi.data.EventData;
import com.greenaddress.greenapi.data.SubaccountData;
import com.greenaddress.greenapi.data.TransactionData;
import com.greenaddress.greenbits.ui.GAFragment;
import com.greenaddress.greenbits.ui.GaActivity;
import com.greenaddress.greenbits.ui.R;
import com.greenaddress.greenbits.ui.UI;
import com.greenaddress.greenbits.ui.accounts.AccountView;
import com.greenaddress.greenbits.ui.accounts.SubaccountSelectActivity;
import com.greenaddress.greenbits.ui.assets.AssetsSelectActivity;
import com.greenaddress.greenbits.ui.components.BottomOffsetDecoration;
import com.greenaddress.greenbits.ui.components.DividerItem;
import com.greenaddress.greenbits.ui.preferences.PrefKeys;
import com.greenaddress.greenbits.ui.receive.ReceiveActivity;
import com.greenaddress.greenbits.ui.send.ScanActivity;

import org.bitcoinj.core.Sha256Hash;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Observer;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import static android.app.Activity.RESULT_OK;
import static android.content.Context.MODE_PRIVATE;
import static android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION;

import static com.greenaddress.greenbits.ui.TabbedMainActivity.REQUEST_SELECT_ASSET;
import static com.greenaddress.greenbits.ui.TabbedMainActivity.REQUEST_SELECT_SUBACCOUNT;
import static com.greenaddress.greenbits.ui.TabbedMainActivity.REQUEST_TX_DETAILS;

public class MainFragment extends GAFragment implements View.OnClickListener, ListTransactionsAdapter.OnTxSelected {

    private static final String TAG = MainFragment.class.getSimpleName();
    private static final int TX_PER_PAGE = 15;

    private final List<TransactionData> mTxItems = new ArrayList<>();
    private int mActiveAccount = 0;
    private SubaccountData mSubaccount;
    private Integer mPageLoaded = 0;
    private Integer mLastPage = Integer.MAX_VALUE;
    private boolean isLoading = false;

    private AccountView mAccountView;
    private SwipeRefreshLayout mSwipeRefreshLayout;
    private ListTransactionsAdapter mTransactionsAdapter;
    private LinearLayoutManager mLayoutManager = new LinearLayoutManager(getActivity());
    private TextView mAssetsSelection;
    private TextView mSwitchNetwork;
    private View mView;
    private View mCard;
    private TextView mCardTitle;
    private TextView mCardMessage;
    private Button mCardAction;

    private Disposable newTransactionDisposable;
    private Disposable blockDisposable;
    private Disposable subaccountDisposable;
    private Disposable transactionDisposable;
    private Disposable settingsDisposable;

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        Log.d(TAG, "onCreateView -> " + TAG);

        mView = inflater.inflate(R.layout.fragment_main, container, false);
        if (isZombie())
            return mView;

        mCard = UI.find(mView, R.id.card);
        mCardTitle = UI.find(mView, R.id.cardTitle);
        mCardMessage = UI.find(mView, R.id.cardMessage);
        mCardAction = UI.find(mView, R.id.buttonCardAction);

        // Setup recycler & adapter
        final RecyclerView txView = UI.find(mView, R.id.mainTransactionList);
        txView.setHasFixedSize(true);
        txView.addItemDecoration(new DividerItem(getActivity()));
        txView.setLayoutManager(mLayoutManager);
        float offsetPx = getResources().getDimension(R.dimen.adapter_bar);
        final BottomOffsetDecoration bottomOffsetDecoration = new BottomOffsetDecoration((int) offsetPx);
        txView.addItemDecoration(bottomOffsetDecoration);
        mTransactionsAdapter = new ListTransactionsAdapter(getGaActivity(), getNetwork(),  mTxItems,
                Bridge.INSTANCE.getSpv(), this);
        txView.setAdapter(mTransactionsAdapter);
        txView.addOnScrollListener(recyclerViewOnScrollListener);

        // FIXME, more efficient to use swap
        // txView.swapAdapter(lta, false);
        mSwipeRefreshLayout = UI.find(mView, R.id.mainTransactionListSwipe);
        mSwipeRefreshLayout.setColorSchemeColors(ContextCompat.getColor(getContext(), R.color.accent));
        mSwipeRefreshLayout.setOnRefreshListener(() -> {
            Log.d(TAG, "onRefresh -> " + TAG);
            update();
        });

        // Setup account card view
        mAccountView = UI.find(mView, R.id.accountView);
        mAccountView.listMode(true);
        mAccountView.setOnClickListener(this);
        mAccountView.setSession(getSession());
        if (getSession().isTwoFAReset())
            mAccountView.hideActions();
        else
            mAccountView.showActions(getSession().isWatchOnly());

        mAssetsSelection = UI.find(mView, R.id.assetsSelection);
        mAssetsSelection.setOnClickListener(v -> startActivityForResult(new Intent(getGaActivity(),
                                                                                   AssetsSelectActivity.class),
                                                                        REQUEST_SELECT_ASSET));

        mActiveAccount = Bridge.INSTANCE.getActiveAccount();

        UI.find(mView, R.id.buttonCardAction).setOnClickListener(v -> {
            Bridge.INSTANCE.twoFactorResetDialog(getActivity());
        });

        return mView;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isZombie())
            return;
        if (blockDisposable != null)
            blockDisposable.dispose();
        if (transactionDisposable != null)
            transactionDisposable.dispose();
        if (subaccountDisposable != null)
            subaccountDisposable.dispose();
        if (newTransactionDisposable != null)
            newTransactionDisposable.dispose();
        if (settingsDisposable != null)
            settingsDisposable.dispose();
    }

    @Override
    public void onResume () {
        super.onResume();
        if (isZombie())
            return;

        // on new block received
        blockDisposable = getSession().getNotificationModel().getBlockObservable()
                          .observeOn(AndroidSchedulers.mainThread())
                          .subscribe((blockHeight) -> {
            mTransactionsAdapter.setCurrentBlock(blockHeight);
            mTransactionsAdapter.notifyDataSetChanged();

            // check for pending/uncofirmed txs
            if (hasMempoolTxs())
                updateTransactions(true);
        });

        // on new transaction received
        settingsDisposable = getSession().getNotificationModel().getSettingsObservable()
                             .observeOn(AndroidSchedulers.mainThread())
                             .subscribe((transaction) -> {
            mTransactionsAdapter.notifyDataSetChanged();
            update();
        });

        // on new transaction received
        newTransactionDisposable = getSession().getNotificationModel().getTransactionObservable()
                                   .observeOn(AndroidSchedulers.mainThread())
                                   .subscribe((transaction) -> {
            update();
        });

        // Update information
        if (mSwipeRefreshLayout != null)
            mSwipeRefreshLayout.setRefreshing(true);


        if(getSession().isTwoFAReset()){
            mAccountView.hideActions();
        }else{
            mAccountView.showActions(getSession().isWatchOnly());
        }


        update();
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, @Nullable final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SELECT_SUBACCOUNT && resultCode == RESULT_OK) {
            mActiveAccount = Bridge.INSTANCE.getActiveAccount();
        }
    }

    private void updateCard(){
        TwoFactorReset twoFactorReset = getSession().getTwoFAReset();

        if(twoFactorReset != null){

            if(twoFactorReset.isDisputed()){
                mCardTitle.setText(R.string.id_2fa_dispute_in_progress);
                mCardMessage.setText(R.string.id_warning_wallet_locked_by);
            }else{
                mCardTitle.setText(R.string.id_2fa_reset_in_progress);
                mCardMessage.setText(getString(R.string.id_your_wallet_is_locked_for_a, twoFactorReset.getDaysRemaining()));
            }
            mCardAction.setText(R.string.id_learn_more);
        }

        mCard.setVisibility(twoFactorReset != null ? View.VISIBLE : View.GONE);
    }

    private void update() {
        updateCard();

        subaccountDisposable = Observable.just(getSession())
                               .observeOn(Schedulers.computation())
                               .map((session) -> {
            try {
                return session.getSubAccount(getGaActivity(), mActiveAccount);
            } catch (final Exception e) {
                return session.getSubAccount(getGaActivity(), 0);
            }
        })
                               .observeOn(AndroidSchedulers.mainThread())
                               .subscribe((subaccount) -> {
            mSubaccount = subaccount;
            final Map<String, Long> balance = getBalance();
            final long pointer = subaccount.getPointer();
            final String defaultName = pointer == 0 ? getString(R.string.id_main_account) : getString(R.string.id_account) + " " + pointer;
            mAccountView.setTitle(subaccount.getNameWithDefault(defaultName));
            mAccountView.setBalance(balance.get("btc").longValue());
            mAssetsSelection.setVisibility(getNetwork().getLiquid() ? View.VISIBLE : View.GONE);
            mAssetsSelection.setText(balance.size() == 1 ?
                                     getString(R.string.id_d_asset_in_this_account, balance.size()) :
                                     getString(R.string.id_d_assets_in_this_account, balance.size()));
            // Load transactions after subaccount data because
            // ledger HW doesn't support parallel operations
            updateTransactions(true);
        }, (final Throwable e) -> {
            Log.d(TAG, e.getLocalizedMessage());
        });
    }

    private boolean hasMempoolTxs() {
        for (final TransactionData tx : mTxItems) {
            if (tx.getBlockHeight() == 0)
                return true;
        }
        return false;
    }

    private void updateTransactions(final boolean clean) {
        if (clean) {
            mPageLoaded = 0;
            mLastPage = Integer.MAX_VALUE;
        }
        transactionDisposable = Observable.just(getSession())
                                .observeOn(Schedulers.computation())
                                .map((session) -> {
            return getTransactions(mActiveAccount);
        })
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe((transactions) -> {
            isLoading = false;
            if (mSwipeRefreshLayout != null)
                mSwipeRefreshLayout.setRefreshing(false);

            final Sha256Hash oldTop = !mTxItems.isEmpty() ? mTxItems.get(0).getTxhashAsSha256Hash() : null;
            if (clean)
                mTxItems.clear();
            mTxItems.addAll(transactions);
            mTransactionsAdapter.setCurrentBlock(getSession().getNotificationModel().getBlockHeight());
            mTransactionsAdapter.notifyDataSetChanged();
            showTxView(!mTxItems.isEmpty());

            final Sha256Hash newTop = !mTxItems.isEmpty() ? mTxItems.get(0).getTxhashAsSha256Hash() : null;
            if (oldTop != null && newTop != null && !oldTop.equals(newTop)) {
                // A new tx has arrived; scroll to the top to show it
                final RecyclerView recyclerView = UI.find(mView, R.id.mainTransactionList);
                recyclerView.smoothScrollToPosition(0);
            }
        }, (final Throwable e) -> {
            Log.d(TAG, e.getLocalizedMessage());
            isLoading = false;
            if (mSwipeRefreshLayout != null)
                mSwipeRefreshLayout.setRefreshing(false);
        });
    }

    private List<TransactionData> getTransactions(final int subaccount) throws Exception {
        final List<TransactionData> txs = getSession().getTransactions(
            getGaActivity(), subaccount, mPageLoaded * TX_PER_PAGE, TX_PER_PAGE);
        if (txs.size() < TX_PER_PAGE)
            mLastPage = mPageLoaded;
        mPageLoaded++;
        return txs;
    }

    private Map<String, Long> getBalance() {
        if (mSubaccount == null)
            return new HashMap<String, Long>();
        return mSubaccount.getSatoshi();
    }

    // TODO: Called when a new verified transaction is seen
    public void onVerifiedTx(final Observer observer) {
        final RecyclerView txView = UI.find(mView, R.id.mainTransactionList);
        txView.getAdapter().notifyDataSetChanged();
    }

    private void showTxView(final boolean doShowTxList) {
        UI.showIf(doShowTxList, UI.find(mView, R.id.mainTransactionList));
        UI.showIf(!doShowTxList, UI.find(mView, R.id.emptyListText));
    }

    @Override
    public void setUserVisibleHint(final boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        final GaActivity activity = getGaActivity();
        if (isVisibleToUser && activity != null) {
            activity.hideKeyboardFrom(null); // Current focus
        }
    }

    @Override
    public void onClick(final View view) {
        final ObjectMapper mObjectMapper = new ObjectMapper();
        view.setEnabled(false);
        if (view.getId() == R.id.receiveButton) {
            view.setEnabled(true);
            final Intent intent = new Intent(getActivity(), ReceiveActivity.class);
            try {
                final String text = mObjectMapper.writeValueAsString(mSubaccount);
                intent.putExtra("SUBACCOUNT", text);
                getActivity().startActivity(intent);
            } catch (final Exception e) {
                e.printStackTrace();
            }
        } else if (view.getId() == R.id.sendButton) {
            view.setEnabled(true);
            if (getBalance() == null || getBalance().get("btc") == null)
                return;
            if (getNetwork().getLiquid() && getBalance().get("btc") == 0L) {
                UI.popup(getGaActivity(), R.string.id_warning, R.string.id_receive, R.string.id_cancel)
                .content(R.string.id_insufficient_lbtc_to_send_a)
                .onPositive((dialog, which) -> {
                    try {
                        final Intent intent = new Intent(getActivity(), ReceiveActivity.class);
                        final String text = mObjectMapper.writeValueAsString(mSubaccount);
                        intent.putExtra("SUBACCOUNT", text);
                        getActivity().startActivity(intent);
                    } catch (final Exception e) {
                        e.printStackTrace();
                    }
                })
                .build().show();
                return;
            }
            final Intent intent = new Intent(getActivity(), ScanActivity.class);
            intent.putExtra(PrefKeys.SWEEP, getSession().isWatchOnly());
            startActivity(intent);
        } else if (view.getId() == R.id.selectSubaccount) {
            view.setEnabled(true);
            final Intent intent = new Intent(getActivity(), SubaccountSelectActivity.class);
            intent.setFlags(FLAG_ACTIVITY_NO_ANIMATION);
            startActivityForResult(intent, REQUEST_SELECT_SUBACCOUNT);
        }
    }

    private void loadMoreItems() {
        Log.d(TAG, "loadMoreItems");
        isLoading = true;
        updateTransactions(false);
    }

    private RecyclerView.OnScrollListener recyclerViewOnScrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(final RecyclerView recyclerView, final int newState) {
            super.onScrollStateChanged(recyclerView, newState);
        }

        @Override
        public void onScrolled(final RecyclerView recyclerView, final int dx, final int dy) {
            super.onScrolled(recyclerView, dx, dy);
            final int visibleItemCount = mLayoutManager.getChildCount();
            final int totalItemCount = mLayoutManager.getItemCount();
            final int firstVisibleItemPosition = mLayoutManager.findFirstVisibleItemPosition();
            final boolean isLastPage = mPageLoaded >= mLastPage;
            if (!isLoading && !isLastPage) {
                if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount
                    && firstVisibleItemPosition >= 0) {
                    loadMoreItems();
                }
            }
        }
    };

    @Override
    public void onSelected(final TransactionData tx) {
        final Intent txIntent = new Intent(getActivity(), TransactionActivity.class);
        final HashMap<String, Long> balance = new HashMap<String, Long>(getBalance());
        txIntent.putExtra("TRANSACTION", tx);
        txIntent.putExtra("BALANCE", balance);
        startActivityForResult(txIntent, REQUEST_TX_DETAILS);
    }
}
