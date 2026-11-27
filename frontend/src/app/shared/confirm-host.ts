import { Component, inject } from '@angular/core';
import { ConfirmService } from './confirm';
import { Dialog } from './ui';

/** Renders ConfirmService requests; app.ts loads it with @defer so the dialog code stays out of the initial bundle. */
@Component({
  selector: 'app-confirm-host',
  imports: [Dialog],
  template: `
    <app-dialog [open]="!!confirm.pending()" [heading]="confirm.pending()?.title ?? ''" (closed)="confirm.settle(false)">
      <p class="text-sm text-muted">{{ confirm.pending()?.message }}</p>
      <div dialog-footer class="flex justify-end gap-2 border-t border-line p-4">
        <button type="button" class="btn-secondary" (click)="confirm.settle(false)">Cancel</button>
        <button type="button" [class]="confirm.pending()?.tone === 'danger' ? 'btn-danger' : 'btn-primary'"
                (click)="confirm.settle(true)">{{ confirm.pending()?.confirmLabel ?? 'Confirm' }}</button>
      </div>
    </app-dialog>
  `,
})
export class ConfirmHost {
  protected readonly confirm = inject(ConfirmService);
}
