/*
 *   Copyright 2015 Benoit LETONDOR
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.benoitletondor.easybudgetapp.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.os.Bundle;

import com.getbase.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.appcompat.widget.SwitchCompat;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.benoitletondor.easybudgetapp.R;
import com.benoitletondor.easybudgetapp.helper.UIHelper;
import com.benoitletondor.easybudgetapp.helper.CurrencyHelper;
import com.benoitletondor.easybudgetapp.model.Expense;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

/**
 * Activity to add a new expense
 *
 * @author Benoit LETONDOR
 */
public class ExpenseEditActivity extends DBActivity
{
    /**
     * Save floating action button
     */
    private FloatingActionButton fab;
    /**
     * Edit text that contains the description
     */
    private EditText             descriptionEditText;
    /**
     * Edit text that contains the amount
     */
    private EditText             amountEditText;
    /**
     * Button for date selection
     */
    private Button               dateButton;
    /**
     * Textview that displays the type of expense
     */
    private TextView             expenseType;
    /**
     * Expense that is being edited (will be null if it's a new one)
     */
    private Expense              expense;
    /**
     * The date of the expense
     */
    private Date                 date;
    /**
     * Is the new expense a revenue
     */
    private boolean isRevenue = false;

// -------------------------------------->

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_expense_edit);

        setSupportActionBar(findViewById(R.id.toolbar));

        Objects.requireNonNull(getSupportActionBar()).setDisplayShowHomeEnabled(true);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        date = new Date(getIntent().getLongExtra("date", 0));

        if ( isEdit() )
        {
            expense = getIntent().getParcelableExtra("expense");
            isRevenue = Objects.requireNonNull(expense).isRevenue();
            date = expense.getDate();

            setTitle(isRevenue ? R.string.title_activity_edit_income : R.string.title_activity_edit_expense);
        }

        setUpButtons();
        setUpTextFields();
        setUpDateButton();

        setResult(RESULT_CANCELED);

        if ( UIHelper.willAnimateActivityEnter(this) )
        {
            UIHelper.animateActivityEnter(this, new AnimatorListenerAdapter()
            {
                @Override
                public void onAnimationEnd(Animator animation)
                {
                    UIHelper.setFocus(descriptionEditText);
                    UIHelper.showFAB(fab);
                }
            });
        }
        else
        {
            UIHelper.setFocus(descriptionEditText);
            UIHelper.showFAB(fab);
        }
    }

// ----------------------------------->

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item)
    {
        int id = item.getItemId();

        if( id == android.R.id.home ) // Back button of the actionbar
        {
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

// ----------------------------------->

    /**
     * Is the current action an edit (vs a creation of a new one)
     *
     * @return
     */
    private boolean isEdit()
    {
        return getIntent().hasExtra("expense");
    }

    /**
     * Validate user inputs
     *
     * @return true if user inputs are ok, false otherwise
     */
    private boolean validateInputs()
    {
        boolean ok = true;

        String description = descriptionEditText.getText().toString();
        if( description.trim().isEmpty() )
        {
            descriptionEditText.setError(getResources().getString(R.string.no_description_error));
            ok = false;
        }

        String amount = amountEditText.getText().toString();
        if( amount.trim().isEmpty() )
        {
            amountEditText.setError(getResources().getString(R.string.no_amount_error));
            ok = false;
        }
        else
        {
            try
            {
                double value = Double.valueOf(amount);
                if( value <= 0 )
                {
                    amountEditText.setError(getResources().getString(R.string.negative_amount_error));
                    ok = false;
                }
            }
            catch(Exception e)
            {
                amountEditText.setError(getResources().getString(R.string.invalid_amount));
                ok = false;
            }
        }

        return ok;
    }

    /**
     * Set-up revenue and payment buttons
     */
    private void setUpButtons()
    {
        expenseType = findViewById(R.id.expense_type_tv);

        SwitchCompat expenseTypeSwitch = findViewById(R.id.expense_type_switch);
        expenseTypeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isRevenue = isChecked;
            setExpenseTypeTextViewLayout();
        });

        // Init value to checked if already a revenue (can be true if we are editing an expense)
        if( isRevenue )
        {
            expenseTypeSwitch.setChecked(true);
            setExpenseTypeTextViewLayout();
        }

        fab = findViewById(R.id.save_expense_fab);
        fab.setOnClickListener(v -> {
            if (validateInputs())
            {
                double value = Double.parseDouble(amountEditText.getText().toString());

                Expense expenseToSave;
                if (expense == null)
                {
                    expenseToSave = new Expense(descriptionEditText.getText().toString(), isRevenue ? -value : value, date);
                }
                else
                {
                    expenseToSave = expense;
                    expenseToSave.setTitle(descriptionEditText.getText().toString());
                    expenseToSave.setAmount(isRevenue ? -value : value);
                    expenseToSave.setDate(date);
                }

                db.persistExpense(expenseToSave);

                setResult(RESULT_OK);
                finish();
            }
        });
    }

    /**
     * Set revenue text view layout
     */
    private void setExpenseTypeTextViewLayout()
    {
        if( isRevenue )
        {
            expenseType.setText(R.string.income);
            expenseType.setTextColor(ContextCompat.getColor(this, R.color.budget_green));

            setTitle(isEdit() ? R.string.title_activity_edit_income : R.string.title_activity_add_income);
        }
        else
        {
            expenseType.setText(R.string.payment);
            expenseType.setTextColor(ContextCompat.getColor(this, R.color.budget_red));

            setTitle(isEdit() ? R.string.title_activity_edit_expense : R.string.title_activity_add_expense);
        }
    }

    /**
     * Set up text field focus behavior
     */
    private void setUpTextFields()
    {
        ((TextInputLayout) findViewById(R.id.amount_inputlayout)).setHint(getResources().getString(R.string.amount, CurrencyHelper.getUserCurrency(this).getSymbol()));

        descriptionEditText = findViewById(R.id.description_edittext);

        if( expense != null )
        {
            descriptionEditText.setText(expense.getTitle());
            descriptionEditText.setSelection(descriptionEditText.getText().length()); // Put focus at the end of the text
        }

        amountEditText = findViewById(R.id.amount_edittext);
        UIHelper.preventUnsupportedInputForDecimals(amountEditText);

        if( expense != null )
        {
            amountEditText.setText(CurrencyHelper.getFormattedAmountValue(Math.abs(expense.getAmount())));
        }
    }

    /**
     * Set up the date button
     */
    private void setUpDateButton()
    {
        dateButton = findViewById(R.id.date_button);
        UIHelper.removeButtonBorder(dateButton); // Remove border on lollipop

        updateDateButtonDisplay();

        dateButton.setOnClickListener(v -> {
            DatePickerDialogFragment fragment = new DatePickerDialogFragment(date, (view, year, monthOfYear, dayOfMonth) -> {
                Calendar cal = Calendar.getInstance();

                cal.set(Calendar.YEAR, year);
                cal.set(Calendar.MONTH, monthOfYear);
                cal.set(Calendar.DAY_OF_MONTH, dayOfMonth);

                date = cal.getTime();
                updateDateButtonDisplay();
            });

            fragment.show(getSupportFragmentManager(), "datePicker");
        });
    }

    private void updateDateButtonDisplay()
    {
        SimpleDateFormat formatter = new SimpleDateFormat(getResources().getString(R.string.add_expense_date_format), Locale.getDefault());
        dateButton.setText(formatter.format(date));
    }
}
