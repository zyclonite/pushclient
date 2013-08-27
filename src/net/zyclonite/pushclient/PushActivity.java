package net.zyclonite.pushclient;

import net.zyclonite.pushclient.R;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

public class PushActivity extends Activity {
	public static final String TAG = "PushActivity";

	private final OnClickListener mClicked = new OnClickListener() {
		public void onClick(View v) {
			switch (v.getId()) {
			case R.id.start:
				PushService.actionStart(PushActivity.this);
				break;
			case R.id.stop:
				PushService.actionStop(PushActivity.this);
				break;
			/*case R.id.ping:
				PushService.actionPing(PushActivity.this);
				break;*/
			}
		}
	};

	@Override
	public void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		setContentView(R.layout.main);

		setUpdateListerner();
		findViewById(R.id.start).setOnClickListener(mClicked);
		findViewById(R.id.stop).setOnClickListener(mClicked);
		/*findViewById(R.id.ping).setOnClickListener(mClicked);*/
	}

	private final void setUpdateListerner() {
		PushService.setUpdateListener(new ServiceUpdateUIListener() {
			public void updateUI(final String message) {
				runOnUiThread(new Runnable() {
					public void run() {
						TextView textview = (TextView)findViewById(R.id.LogView);
						textview.append(message);
					}
				});
			}
		});
	}
}