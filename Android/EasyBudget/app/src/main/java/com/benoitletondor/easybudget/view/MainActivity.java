package com.benoitletondor.easybudget.view;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.benoitletondor.easybudget.R;
import com.benoitletondor.easybudget.helper.CompatHelper;
import com.benoitletondor.easybudget.helper.ParameterKeys;
import com.benoitletondor.easybudget.helper.Parameters;
import com.benoitletondor.easybudget.model.Expense;
import com.benoitletondor.easybudget.view.main.calendar.CalendarFragment;
import com.benoitletondor.easybudget.view.main.ExpensesRecyclerViewAdapter;
import com.getbase.floatingactionbutton.FloatingActionButton;
import com.getbase.floatingactionbutton.FloatingActionsMenu;
import com.roomorama.caldroid.CaldroidFragment;
import com.roomorama.caldroid.CaldroidListener;
import com.roomorama.caldroid.WeekdayArrayAdapter;

import java.util.Calendar;
import java.util.Date;

/**
 * Main activity containing Calendar and List of expenses
 *
 * @author Benoit LETONDOR
 */
public class MainActivity extends DBActivity
{
    public static final int ADD_EXPENSE_ACTIVITY_CODE = 101;
    public static final int MANAGE_MONTHLY_EXPENSE_ACTIVITY_CODE = 102;
    public static final String INTENT_EXPENSE_DELETED = "intent.expense.deleted";

    private static final String CALENDAR_SAVED_STATE = "calendar_saved_state";
    private static final String RECYCLE_VIEW_SAVED_DATE = "recycleViewSavedDate";

    private BroadcastReceiver receiver;

    private CalendarFragment            calendarFragment;
    private RecyclerView                expensesRecyclerView;
    private ExpensesRecyclerViewAdapter expensesViewAdapter;

    private TextView budgetLine;

// ------------------------------------------>

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        budgetLine = (TextView) findViewById(R.id.budgetLine);
        initCalendarFragment(savedInstanceState);
        initRecyclerView(savedInstanceState);

        // Register receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(INTENT_EXPENSE_DELETED);

        receiver = new BroadcastReceiver()
        {
            @Override
            public void onReceive(Context context, Intent intent)
            {
                if( INTENT_EXPENSE_DELETED.equals(intent.getAction()) )
                {
                    final Expense expense = (Expense) intent.getSerializableExtra("expense");

                    if( db.deleteExpense(expense) )
                    {
                        refreshAllForDate(expensesViewAdapter.getDate());

                        Snackbar snackbar = Snackbar.make(expensesRecyclerView, R.string.expense_delete_snackbar_text, Snackbar.LENGTH_LONG);
                        snackbar.setAction(R.string.cancel, new View.OnClickListener()
                        {
                            @Override
                            public void onClick(View v)
                            {
                                db.addExpense(expense);

                                refreshAllForDate(expensesViewAdapter.getDate());
                            }
                        });

                        snackbar.show();
                    }
                    else
                    {
                        // TODO warn user of error
                    }

                }
            }
        };

        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(receiver, filter);
    }

    @Override
    protected void onDestroy()
    {
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(receiver);

        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState)
    {
        if (calendarFragment != null)
        {
            calendarFragment.saveStatesToKey(outState, CALENDAR_SAVED_STATE);
        }

        if( expensesRecyclerView.getAdapter() != null && (expensesRecyclerView.getAdapter() instanceof ExpensesRecyclerViewAdapter) )
        {
            outState.putSerializable(RECYCLE_VIEW_SAVED_DATE, ((ExpensesRecyclerViewAdapter) expensesRecyclerView.getAdapter()).getDate());
        }

        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);

        if( requestCode == ADD_EXPENSE_ACTIVITY_CODE || requestCode == MANAGE_MONTHLY_EXPENSE_ACTIVITY_CODE )
        {
            if( resultCode == RESULT_OK )
            {
                refreshAllForDate(calendarFragment.getSelectedDate());
            }
        }
    }

// ------------------------------------------>

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if ( id == R.id.action_settings)
        {
            return true;
        }
        else if( id == R.id.action_balance )
        {
            final int currentBalance = -db.getBalanceForDay(new Date());

            View dialogView = getLayoutInflater().inflate(R.layout.dialog_adjust_balance, null);
            final EditText amountEditText = (EditText) dialogView.findViewById(R.id.balance_amount);
            amountEditText.setText(String.valueOf(currentBalance));

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.adjust_balance_title);
            builder.setMessage(R.string.adjust_balance_message);
            builder.setView(dialogView);
            builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener()
            {
                @Override
                public void onClick(DialogInterface dialog, int which)
                {
                    dialog.dismiss();
                }
            });
            builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener()
            {
                @Override
                public void onClick(DialogInterface dialog, int which)
                {
                    // Ajust balance
                    int newBalance = Integer.valueOf(amountEditText.getText().toString());
                    int diff = newBalance - currentBalance;

                    final Expense expense = new Expense(getResources().getString(R.string.adjust_balance_expense_title), -diff, new Date());
                    db.addExpense(expense);

                    refreshAllForDate(expensesViewAdapter.getDate());
                    dialog.dismiss();

                    //Show snackbar TODO money formatting
                    Snackbar snackbar = Snackbar.make(expensesRecyclerView, String.format(getResources().getString(R.string.adjust_balance_snackbar_text), newBalance+"€"), Snackbar.LENGTH_LONG);
                    snackbar.setAction(R.string.cancel, new View.OnClickListener()
                    {
                        @Override
                        public void onClick(View v)
                        {
                            db.deleteExpense(expense);

                            refreshAllForDate(expensesViewAdapter.getDate());
                        }
                    });

                    snackbar.show();
                }
            });

            builder.show();

            return true;
        }

        return super.onOptionsItemSelected(item);
    }

// ------------------------------------------>

    /**
     * Update the balance for the given day
     * FIXME optim, translate
     *
     * @param day
     */
    private void updateBalanceDisplayForDay(@NonNull Date day)
    {
        int balance = - db.getBalanceForDay(day);

        budgetLine.setText("ACCOUNT BALANCE : " + balance + " €"); //TODO translate

        if( balance <= 0 )
        {
            budgetLine.setBackgroundResource(R.color.budget_red);
        }
        else if( balance < 100 )
        {
            budgetLine.setBackgroundResource(R.color.budget_orange);
        }
        else
        {
            budgetLine.setBackgroundResource(R.color.budget_green);
        }
    }

// ------------------------------------------>

    private void initCalendarFragment(Bundle savedInstanceState)
    {
        calendarFragment = new CalendarFragment();

        if( savedInstanceState != null && savedInstanceState.containsKey(CALENDAR_SAVED_STATE) )
        {
            calendarFragment.restoreStatesFromKey(savedInstanceState, CALENDAR_SAVED_STATE);
        }
        else
        {
            Bundle args = new Bundle();
            Calendar cal = Calendar.getInstance();
            args.putInt(CaldroidFragment.MONTH, cal.get(Calendar.MONTH) + 1);
            args.putInt(CaldroidFragment.YEAR, cal.get(Calendar.YEAR));
            args.putBoolean(CaldroidFragment.ENABLE_SWIPE, true);
            args.putBoolean(CaldroidFragment.SIX_WEEKS_IN_CALENDAR, false);
            args.putInt(CalendarFragment.START_DAY_OF_WEEK, CalendarFragment.MONDAY);
            args.putBoolean(CalendarFragment.ENABLE_CLICK_ON_DISABLED_DATES, false);

            calendarFragment.setArguments(args);
            calendarFragment.setSelectedDates(new Date(), new Date());

            Date minDate = new Date(Parameters.getInstance(this).getLong(ParameterKeys.INIT_DATE, new Date().getTime()));
            calendarFragment.setMinDate(minDate);
        }

        WeekdayArrayAdapter.textColor = ContextCompat.getColor(this, R.color.secondary_text);

        final CaldroidListener listener = new CaldroidListener()
        {
            @Override
            public void onSelectDate(Date date, View view)
            {
                calendarFragment.setSelectedDates(date, date);
                refreshAllForDate(date);
            }

            @Override
            public void onChangeMonth(int month, int year)
            {

            }

            @Override
            public void onCaldroidViewCreated()
            {
                Button leftButton = calendarFragment.getLeftArrowButton();
                Button rightButton = calendarFragment.getRightArrowButton();
                TextView textView = calendarFragment.getMonthTitleTextView();

                textView.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.primary_text));

                leftButton.setText("<");
                leftButton.setTextSize(25);
                leftButton.setGravity(Gravity.CENTER);
                leftButton.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.primary));
                leftButton.setBackgroundResource(R.drawable.calendar_month_switcher_button_drawable);

                rightButton.setText(">");
                rightButton.setTextSize(25);
                rightButton.setGravity(Gravity.CENTER);
                rightButton.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.primary));
                rightButton.setBackgroundResource(R.drawable.calendar_month_switcher_button_drawable);

                // Remove border on lollipop
                CompatHelper.removeButtonBorder(leftButton);
                CompatHelper.removeButtonBorder(rightButton);
            }
        };

        calendarFragment.setCaldroidListener(listener);

        FragmentTransaction t = getSupportFragmentManager().beginTransaction();
        t.replace(R.id.calendarView, calendarFragment);
        t.commit();
    }

    private void initRecyclerView(Bundle savedInstanceState)
    {
        /*
         * FAB
         */
        final FloatingActionsMenu menu = (FloatingActionsMenu) findViewById(R.id.fab_choices);

        final View background = MainActivity.this.findViewById(R.id.fab_choices_background);
        final float backgroundAlpha = 0.8f;
        final long backgroundAnimationDuration = 200;

        background.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                menu.collapse();
            }
        });

        menu.setOnFloatingActionsMenuUpdateListener(new FloatingActionsMenu.OnFloatingActionsMenuUpdateListener()
        {
            @Override
            public void onMenuExpanded()
            {
                AlphaAnimation fadeInAnimation = new AlphaAnimation(0.0f, backgroundAlpha);
                fadeInAnimation.setDuration(backgroundAnimationDuration);
                fadeInAnimation.setFillAfter(true);
                fadeInAnimation.setAnimationListener(new Animation.AnimationListener()
                {
                    @Override
                    public void onAnimationStart(Animation animation)
                    {
                        background.setVisibility(View.VISIBLE);
                        background.setClickable(true);
                    }

                    @Override
                    public void onAnimationEnd(Animation animation)
                    {

                    }

                    @Override
                    public void onAnimationRepeat(Animation animation)
                    {

                    }
                });

                background.startAnimation(fadeInAnimation);
            }

            @Override
            public void onMenuCollapsed()
            {
                AlphaAnimation fadeOutAnimation = new AlphaAnimation(backgroundAlpha, 0.0f);
                fadeOutAnimation.setDuration(backgroundAnimationDuration);
                fadeOutAnimation.setFillAfter(true);
                fadeOutAnimation.setAnimationListener(new Animation.AnimationListener()
                {
                    @Override
                    public void onAnimationStart(Animation animation)
                    {

                    }

                    @Override
                    public void onAnimationEnd(Animation animation)
                    {
                        background.setVisibility(View.GONE);
                        background.setClickable(false);
                    }

                    @Override
                    public void onAnimationRepeat(Animation animation)
                    {

                    }
                });

                background.startAnimation(fadeOutAnimation);
            }
        });

        FloatingActionButton fabNewExpense = (FloatingActionButton) findViewById(R.id.fab_new_expense);
        fabNewExpense.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                Intent startIntent = new Intent(MainActivity.this, ExpenseEditActivity.class);
                startIntent.putExtra("date", calendarFragment.getSelectedDate());

                ActivityCompat.startActivityForResult(MainActivity.this, startIntent, ADD_EXPENSE_ACTIVITY_CODE, null);

                menu.collapse();
            }
        });

        FloatingActionButton fabNewMonthlyExpense = (FloatingActionButton) findViewById(R.id.fab_new_monthly_expense);
        fabNewMonthlyExpense.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                Intent startIntent = new Intent(MainActivity.this, MonthlyExpenseEditActivity.class);
                startIntent.putExtra("date", calendarFragment.getSelectedDate());

                ActivityCompat.startActivityForResult(MainActivity.this, startIntent, ADD_EXPENSE_ACTIVITY_CODE, null);

                menu.collapse();
            }
        });

        /*
         * Expense Recycler view
         */
        expensesRecyclerView = (RecyclerView) findViewById(R.id.expensesRecyclerView);
        expensesRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        Date date;
        if( savedInstanceState != null && savedInstanceState.containsKey(RECYCLE_VIEW_SAVED_DATE) )
        {
            date = (Date) savedInstanceState.getSerializable(RECYCLE_VIEW_SAVED_DATE);
        }
        else
        {
            date = new Date();
        }

        refreshRecyclerViewForDate(date);
        updateBalanceDisplayForDay(date);
    }

    private void refreshRecyclerViewForDate(@NonNull Date date)
    {
        expensesViewAdapter = new ExpensesRecyclerViewAdapter(this, db, date);
        expensesRecyclerView.setAdapter(expensesViewAdapter);
    }

    private void refreshAllForDate(@NonNull Date date)
    {
        refreshRecyclerViewForDate(date);
        updateBalanceDisplayForDay(date);
        calendarFragment.refreshView();
    }
}