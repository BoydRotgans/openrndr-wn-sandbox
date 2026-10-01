import React from 'react'
import ReactDOM from 'react-dom/client'
import Practice from './Practice'
import { hasAccess } from '../lib/access'
import { copy } from './copy'
import './practice.css'

/** Shut: said in both languages, since whoever arrives here has no page to pick one on. */
function Private() {
  return (
    <div className="p-private">
      <span className="p-mark">WN</span>
      <h1>{copy.nl.private}</h1>
      <p>{copy.nl.privateHint}</p>
      <p className="en">{copy.en.private} {copy.en.privateHint}</p>
    </div>
  )
}

const root = ReactDOM.createRoot(document.getElementById('root')!)
hasAccess('practice').then((open) =>
  root.render(
    <React.StrictMode>
      {open ? <Practice /> : <Private />}
    </React.StrictMode>,
  ),
)
