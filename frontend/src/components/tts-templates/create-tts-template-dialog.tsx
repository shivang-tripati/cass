"use client";
/* eslint-disable @typescript-eslint/no-explicit-any */
import { useState } from "react";
import { useForm, useFieldArray } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui/dialog";
import { FieldGroup } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { TextField } from "@/components/forms/text-field";
import { TextareaField } from "@/components/forms/textarea-field";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { toApiError } from "@/lib/api/error";
import { createTtsTemplate, ttsTemplatesKeys } from "@/lib/api/tts-templates";
import { createTtsTemplateSchema, toCreatePayload, type CreateTtsTemplateValues } from "@/lib/schemas/tts-template-mutation";

export function CreateTtsTemplateDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o:boolean)=>void }){
  const qc = useQueryClient();
  const [alert, setAlert] = useState<string|null>(null);
  const form = useForm<CreateTtsTemplateValues>({ resolver: zodResolver(createTtsTemplateSchema) as any, defaultValues: { name:"", description:"", templateText:"", variables: [] } as any });
  const { fields, append, remove } = useFieldArray({ control: form.control, name: "variables" as any });
  const pending = form.formState.isSubmitting;
  function close(o:boolean){ if(!o){ form.reset(); setAlert(null);} onOpenChange(o); }
  async function onSubmit(v: CreateTtsTemplateValues){
    setAlert(null);
    try{ await createTtsTemplate(toCreatePayload(v)); toast.success("TTS template created (pending approval)"); await qc.invalidateQueries({ queryKey: ttsTemplatesKeys.all }); close(false); }
    catch(e){ const ae=toApiError(e); for(const fe of ae.fieldErrors) if(fe.field in createTtsTemplateSchema.shape) { form.setError(fe.field as any, {message: fe.message}); return; } setAlert(ae.message); }
  }
  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent className="sm:max-w-xl max-h-[85vh] overflow-auto">
        <DialogHeader><DialogTitle>Create TTS template</DialogTitle></DialogHeader>
        {alert && <p role="alert" className="text-sm text-destructive">{alert}</p>}
        <form onSubmit={form.handleSubmit(onSubmit as any)} noValidate>
          <FieldGroup>
            <TextField label="Name" registration={form.register("name")} error={form.formState.errors.name?.message} />
            <TextareaField label="Description" registration={form.register("description")} error={form.formState.errors.description?.message} rows={2} />
            <TextareaField label="Template text" registration={form.register("templateText")} error={form.formState.errors.templateText?.message} rows={4} description="Use {{variableName}} placeholders. Only declared variables allowed." />
            <div className="space-y-2">
              <div className="flex items-center justify-between"><Label>Variables (max 50)</Label><Button type="button" variant="outline" size="sm" onClick={()=>append({ name:"", type:"STRING", required:false } as any)}>Add variable</Button></div>
              {fields.map((f, i)=>(
                <div key={f.id} className="flex gap-2 items-end border p-2 rounded">
                  <div className="flex-1"><Label>Name</Label><Input {...form.register(`variables.${i}.name` as any)} placeholder="firstName" /></div>
                  <div className="w-28"><Label>Type</Label><Select value={form.watch(`variables.${i}.type` as any) ?? "STRING"} onValueChange={v=>form.setValue(`variables.${i}.type` as any, v)}><SelectTrigger><SelectValue /></SelectTrigger><SelectContent><SelectItem value="STRING">STRING</SelectItem><SelectItem value="NUMBER">NUMBER</SelectItem><SelectItem value="BOOLEAN">BOOLEAN</SelectItem><SelectItem value="DATE">DATE</SelectItem></SelectContent></Select></div>
                  <label className="flex items-center gap-1 text-sm"><Checkbox checked={!!form.watch(`variables.${i}.required` as any)} onCheckedChange={v=>form.setValue(`variables.${i}.required` as any, !!v)} /> Required</label>
                  <Button type="button" variant="ghost" size="sm" onClick={()=>remove(i)}>Remove</Button>
                </div>
              ))}
              {form.formState.errors.variables?.message && <p className="text-sm text-destructive">{String(form.formState.errors.variables.message)}</p>}
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={()=>close(false)} disabled={pending}>Cancel</Button>
              <Button type="submit" disabled={pending}>{pending ? <><Spinner/> Creating…</> : "Create"}</Button>
            </DialogFooter>
          </FieldGroup>
        </form>
      </DialogContent>
    </Dialog>
  );
}
